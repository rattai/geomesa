package geomesa.core.index

import java.nio.charset.StandardCharsets
import java.util.Map.Entry
import java.util.{Iterator => JIterator}

import com.google.common.collect.Iterators
import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.Polygon
import geomesa.core._
import geomesa.core.data._
import geomesa.core.index.IndexQueryPlanner._
import geomesa.core.index.QueryHints._
import geomesa.core.iterators.{FEATURE_ENCODING, _}
import org.apache.accumulo.core.client.{BatchScanner, IteratorSetting, Scanner}
import org.apache.accumulo.core.data.{Key, Value, Range => AccRange}
import org.apache.accumulo.core.iterators.user.RegExFilter
import org.apache.hadoop.io.Text
import org.geotools.data.{DataUtilities, Query}
import org.geotools.factory.CommonFactoryFinder
import org.geotools.filter.text.ecql.ECQL
import org.geotools.geometry.jts.ReferencedEnvelope
import org.joda.time.Interval
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.expression.{Literal, PropertyName}
import org.opengis.filter.{Filter, PropertyIsEqualTo, PropertyIsLike}

import scala.collection.JavaConversions._
import scala.util.Random


object IndexQueryPlanner {
  val iteratorPriority_RowRegex                       = 0
  val iteratorPriority_ColFRegex                      = 100
  val iteratorPriority_SpatioTemporalIterator         = 200
  val iteratorPriority_SimpleFeatureFilteringIterator = 300

  trait CloseableIterator[T] extends Iterator[T] {
    def close(): Unit
  }
}

case class IndexQueryPlanner(keyPlanner: KeyPlanner,
                             cfPlanner: ColumnFamilyPlanner,
                             schema:String,
                             featureType: SimpleFeatureType,
                             featureEncoder: SimpleFeatureEncoder) extends Logging {

  def buildFilter(poly: Polygon, interval: Interval): KeyPlanningFilter =
    (IndexSchema.somewhere(poly), IndexSchema.somewhen(interval)) match {
      case (None, None)       =>    AcceptEverythingFilter
      case (None, Some(i))    =>
        if (i.getStart == i.getEnd) DateFilter(i.getStart)
        else                        DateRangeFilter(i.getStart, i.getEnd)
      case (Some(p), None)    =>    SpatialFilter(poly)
      case (Some(p), Some(i)) =>
        if (i.getStart == i.getEnd) SpatialDateFilter(p, i.getStart)
        else                        SpatialDateRangeFilter(p, i.getStart, i.getEnd)
    }


  def netPolygon(poly: Polygon): Polygon = poly match {
    case null => null
    case p if p.covers(IndexSchema.everywhere) =>
      IndexSchema.everywhere
    case p if IndexSchema.everywhere.covers(p) => p
    case _ => poly.intersection(IndexSchema.everywhere).
      asInstanceOf[Polygon]
  }

  def netInterval(interval: Interval): Interval = interval match {
    case null => null
    case _    => IndexSchema.everywhen.overlap(interval)
  }


  // Strategy:
  // 1. Inspect the query
  // 2. Set up the base iterators/scans.
  // 3. Set up the rest of the iterator stack.
  def getIterator(ds: AccumuloDataStore, query: Query) : CloseableIterator[Entry[Key,Value]] = {

    val ff = CommonFactoryFinder.getFilterFactory2
    val isDensity = query.getHints.containsKey(BBOX_KEY)
    val derivedQuery =
      if (isDensity) {
        val env = query.getHints.get(BBOX_KEY).asInstanceOf[ReferencedEnvelope]
        val q1 = new Query(featureType.getTypeName, ff.bbox(ff.property(featureType.getGeometryDescriptor.getLocalName), env))
        DataUtilities.mixQueries(q1, query, "geomesa.mixed.query")
      } else query


    val sourceSimpleFeatureType = DataUtilities.encodeType(featureType)

    val filterVisitor = new FilterToAccumulo(featureType)
    filterVisitor.visit(derivedQuery) match {
      case isEqualTo: PropertyIsEqualTo if !isDensity =>
        attrIdxEqualToQuery(ds, derivedQuery, isEqualTo, filterVisitor)

      case like: PropertyIsLike if !isDensity =>
        if(likeEligible(like))
          attrIdxLikeQuery(ds, derivedQuery, like, filterVisitor)
        else
          stIdxQuery(ds, derivedQuery, like, filterVisitor)

      case cql =>
        stIdxQuery(ds, derivedQuery, cql, filterVisitor)
    }
  }

  val iteratorPriority_AttributeIndexFilteringIterator = 10

  type ARange = org.apache.accumulo.core.data.Range

  val WILDCARD = "%"
  val SINGLE_CHAR = "_"
  val NULLBYTE = Array[Byte](0.toByte)

  /* Like queries that can be handled by current reverse index */
  def likeEligible(filter: PropertyIsLike) = containsNoSingles(filter) && trailingOnlyWildcard(filter)

  /* contains no single character wildcards */
  def containsNoSingles(filter: PropertyIsLike) =
    !(filter.getLiteral.replace("\\\\", "").replace(s"\\$SINGLE_CHAR", "").contains(SINGLE_CHAR))

  def trailingOnlyWildcard(filter: PropertyIsLike) =
    (filter.getLiteral.endsWith(WILDCARD) &&
      filter.getLiteral.indexOf(WILDCARD) == filter.getLiteral.length - WILDCARD.length) ||
      filter.getLiteral.indexOf(WILDCARD) == -1

  def attrIdxLikeQuery(dataStore: AccumuloDataStore,
                       derivedQuery: Query,
                       filter: PropertyIsLike,
                       filterVisitor: FilterToAccumulo) = {

    val expr = filter.getExpression
    val prop = expr match {
      case p: PropertyName => p.getPropertyName
    }

    val literal = filter.getLiteral
    // for now use this
    val value =
      if(literal.endsWith(WILDCARD))
        literal.substring(0, literal.length - WILDCARD.length)
      else
        literal

    val range = org.apache.accumulo.core.data.Range.prefix(formatAttrIdxRow(prop, value))

    getAttrIdxItr(dataStore, derivedQuery, filterVisitor, range)
  }

  def formatAttrIdxRow(prop: String, lit: String) =
    new Text(prop.getBytes(StandardCharsets.UTF_8) ++ NULLBYTE ++ lit.getBytes(StandardCharsets.UTF_8))

  def attrIdxEqualToQuery(dataStore: AccumuloDataStore, derivedQuery: Query, filter: PropertyIsEqualTo, filterVisitor: FilterToAccumulo) = {
    val one = filter.getExpression1
    val two = filter.getExpression2
    val (prop, lit) = (one, two) match {
      case (p: PropertyName, l: Literal) => (p.getPropertyName, l.getValue.toString)
      case (l: Literal, p: PropertyName) => (p.getPropertyName, l.getValue.toString)
    }

    val range = new ARange(formatAttrIdxRow(prop, lit))

    getAttrIdxItr(dataStore, derivedQuery, filterVisitor, range)
  }

  def getAttrIdxItr(dataStore: AccumuloDataStore, derivedQuery: Query, filterVisitor: FilterToAccumulo, range: AccRange) =
    new CloseableIterator[Entry[Key, Value]] {
      val attrScanner = dataStore.createAttrIdxScanner(featureType)

      val spatialOpt =
        for {
            sp    <- Option(filterVisitor.spatialPredicate)
            env  = sp.getEnvelopeInternal
            bbox = List(env.getMinX, env.getMinY, env.getMaxX, env.getMaxY).mkString(",")
        } yield AttributeIndexFilteringIterator.BBOX_KEY -> bbox

      val dtgOpt = Option(filterVisitor.temporalPredicate).map(AttributeIndexFilteringIterator.INTERVAL_KEY -> _.toString)
      val opts = List(spatialOpt, dtgOpt).flatten.toMap
      if(!opts.isEmpty) {
        val cfg = new IteratorSetting(iteratorPriority_AttributeIndexFilteringIterator,
          "attrIndexFilter",
          classOf[AttributeIndexFilteringIterator].getCanonicalName, opts)
        attrScanner.addScanIterator(cfg)
      }

      logger.debug("Range for attribute scan : " + range.toString)
      attrScanner.setRange(range)

      import scala.collection.JavaConversions._
      val ranges = attrScanner.iterator.map(_.getKey.getColumnFamily).map(new AccRange(_))

      val recScanner = if(ranges.hasNext) {
        val recordScanner = dataStore.createRecordScanner(featureType)
        recordScanner.setRanges(ranges.toList)

        val sourceSimpleFeatureType = DataUtilities.encodeType(featureType)
        configureSimpleFeatureFilteringIterator(recordScanner, sourceSimpleFeatureType, None, derivedQuery)
        Some(recordScanner)
      } else None

      val iter = recScanner.map(_.iterator()).getOrElse(Iterators.emptyIterator[Entry[Key, Value]])

      override def close: Unit = {
        recScanner.foreach(_.close)
        attrScanner.close
      }

      override def next: Entry[Key, Value] = iter.next

      override def hasNext: Boolean = iter.hasNext
    }

  def stIdxQuery(ds: AccumuloDataStore, query: Query, rewrittenCQL: Filter, filterVisitor: FilterToAccumulo) = {

    val ecql = Option(ECQL.toCQL(rewrittenCQL))

    val spatial = filterVisitor.spatialPredicate
    val temporal = filterVisitor.temporalPredicate

    // standardize the two key query arguments:  polygon and date-range
    val poly = netPolygon(spatial)
    val interval = netInterval(temporal)

    // figure out which of our various filters we intend to use
    // based on the arguments passed in
    val filter = buildFilter(poly, interval)

    val opoly = IndexSchema.somewhere(poly)
    val oint  = IndexSchema.somewhen(interval)

    // set up row ranges and regular expression filter
    val bs = ds.createBatchScanner(featureType)
    planQuery(bs, filter)

    logger.trace("Configuring batch scanner: " +
                 "Poly: "+ opoly.getOrElse("No poly")+
                 "Interval: " + oint.getOrElse("No interval")+
                 "Filter: " + Option(filter).getOrElse("No Filter")+
                 "ECQL: " + Option(ecql).getOrElse("No ecql")+
                 "Query: " + Option(query).getOrElse("no query"))

    val sourceSimpleFeatureType = DataUtilities.encodeType(featureType)
    val iteratorConfig = IteratorTrigger.chooseIterator(ecql, query, sourceSimpleFeatureType)

    iteratorConfig.iterator match {
      case IndexOnlyIterator  =>
        val transformedSFType = transformedSimpleFeatureType(query).getOrElse(sourceSimpleFeatureType)
        configureIndexIterator(bs, opoly, oint, query, transformedSFType)
      case SpatioTemporalIterator =>
        configureSpatioTemporalIntersectingIterator(bs, opoly, oint, sourceSimpleFeatureType)
    }

    if (iteratorConfig.useSFFI) {
      configureSimpleFeatureFilteringIterator(bs, sourceSimpleFeatureType, ecql, query, poly)
    }

    new CloseableIterator[Entry[Key, Value]] {
      val iter = bs.iterator()
      override def close(): Unit = bs.close()
      override def next(): Entry[Key, Value] = iter.next()
      override def hasNext: Boolean = iter.hasNext
    }
  }

  def configureFeatureEncoding(cfg: IteratorSetting) =
    cfg.addOption(FEATURE_ENCODING, featureEncoder.getName)

  // returns the encoded SimpleFeatureType for the query's transform
  def transformedSimpleFeatureType(query: Query): Option[String] = {
    val transformSchema = Option(query.getHints.get(TRANSFORM_SCHEMA)).map(_.asInstanceOf[SimpleFeatureType])
    transformSchema.map { schema => DataUtilities.encodeType(schema)}
  }

  // store transform information into an Iterator's settings
  def configureTransforms(query:Query,cfg: IteratorSetting) =
    for {
      transformOpt <- Option(query.getHints.get(TRANSFORMS))
      transform    = transformOpt.asInstanceOf[String]
      _            = cfg.addOption(GEOMESA_ITERATORS_TRANSFORM, transform)
      sfType       <- transformedSimpleFeatureType(query)
      _            = cfg.addOption(GEOMESA_ITERATORS_TRANSFORM_SCHEMA,sfType)
    } yield Unit

  // establishes the regular expression that defines (minimally) acceptable rows
  def configureRowRegexIterator(bs: BatchScanner, regex: String) {
    val name = "regexRow-" + randomPrintableString(5)
    val cfg = new IteratorSetting(iteratorPriority_RowRegex, name, classOf[RegExFilter])
    RegExFilter.setRegexs(cfg, regex, null, null, null, false)
    bs.addScanIterator(cfg)
  }

  // returns an iterator over [key,value] pairs where the key is taken from the index row and the value is a SimpleFeature,
  // which is either read directory from the data row  value or generated from the encoded index row value
  // -- for items that either:
  // 1) the GeoHash-box intersects the query polygon; this is a coarse-grained filter
  // 2) the DateTime intersects the query interval; this is a coarse-grained filter
  def configureIndexIterator(bs: BatchScanner,
                             poly: Option[Polygon],
                             interval: Option[Interval],
                             query: Query,
                             featureType: String) {
    val cfg = new IteratorSetting(iteratorPriority_SpatioTemporalIterator,
      "within-" + randomPrintableString(5),classOf[IndexIterator])
    IndexIterator.setOptions(cfg, schema, poly, interval, featureType)
    configureFeatureEncoding(cfg)
    bs.addScanIterator(cfg)
  }

  // returns only the data entries -- no index entries -- for items that either:
  // 1) the GeoHash-box intersects the query polygon; this is a coarse-grained filter
  // 2) the DateTime intersects the query interval; this is a coarse-grained filter
  def configureSpatioTemporalIntersectingIterator(bs: BatchScanner,
                                                  poly: Option[Polygon],
                                                  interval: Option[Interval],
                                                  featureType: String) {
    val cfg = new IteratorSetting(iteratorPriority_SpatioTemporalIterator,
      "within-" + randomPrintableString(5),
      classOf[SpatioTemporalIntersectingIterator])
    SpatioTemporalIntersectingIterator.setOptions(cfg, schema, poly, interval, featureType)
    bs.addScanIterator(cfg)
  }
  // assumes that it receives an iterator over data-only entries, and aggregates
  // the values into a map of attribute, value pairs
  def configureSimpleFeatureFilteringIterator(bs: BatchScanner,
                                              simpleFeatureType: String,
                                              ecql: Option[String],
                                              query: Query,
                                              poly: Polygon = null) {

    val density: Boolean = query.getHints.containsKey(DENSITY_KEY)

    val clazz =
      if(density) classOf[DensityIterator]
      else classOf[SimpleFeatureFilteringIterator]

    val cfg = new IteratorSetting(iteratorPriority_SimpleFeatureFilteringIterator,
      "sffilter-" + randomPrintableString(5),
      clazz)

    configureFeatureEncoding(cfg)
    configureTransforms(query,cfg)
    SimpleFeatureFilteringIterator.setFeatureType(cfg, simpleFeatureType)
    ecql.foreach(SimpleFeatureFilteringIterator.setECQLFilter(cfg, _))

    if(density) {
      val width = query.getHints.get(WIDTH_KEY).asInstanceOf[Integer]
      val height =  query.getHints.get(HEIGHT_KEY).asInstanceOf[Integer]
      DensityIterator.configure(cfg, poly, width, height)
    }

    bs.addScanIterator(cfg)
  }

  def randomPrintableString(length:Int=5) : String = (1 to length).
    map(i => Random.nextPrintableChar()).mkString

  def planQuery(bs: BatchScanner, filter: KeyPlanningFilter): BatchScanner = {
    val keyPlan = keyPlanner.getKeyPlan(filter)
    val columnFamilies = cfPlanner.getColumnFamiliesToFetch(filter)

    // always try to use range(s) to remove easy false-positives
    val accRanges: Seq[org.apache.accumulo.core.data.Range] = keyPlan match {
      case KeyRanges(ranges) => ranges.map(r => new org.apache.accumulo.core.data.Range(r.start, r.end))
      case _ => Seq(new org.apache.accumulo.core.data.Range())
    }
    bs.setRanges(accRanges)

    // always try to set a RowID regular expression
    //@TODO this is broken/disabled as a result of the KeyTier
    keyPlan.toRegex match {
      case KeyRegex(regex) => configureRowRegexIterator(bs, regex)
      case _ => // do nothing
    }

    // if you have a list of distinct column-family entries, fetch them
    columnFamilies match {
      case KeyList(keys) => keys.foreach(cf => bs.fetchColumnFamily(new Text(cf)))
      case _ => // do nothing
    }

    bs
  }
}
