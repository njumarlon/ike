package org.allenai.dictionary

import org.allenai.common.immutable.Interval
import spray.json._
import DefaultJsonProtocol._

case class ClusterReplacement(offset: Interval, clusterPrefix: String)
case object ClusterReplacement {
  implicit val format = jsonFormat2(ClusterReplacement.apply)
}

case class EnvironmentState(query: String, replacements: Seq[ClusterReplacement], dictionaries: Seq[Dictionary])
case object EnvironmentState {
  implicit val format = jsonFormat3(EnvironmentState.apply)
}

case object Environment {
  def replaceClusters(s: String, repls: Seq[ClusterReplacement]): String = 
    replaceClusters(s, repls, 0)
  def replaceClusters(s: String, repls: Seq[ClusterReplacement], start: Int): String = repls match {
    case Nil => s.slice(start, s.size)
    case head :: rest => s.slice(start, head.offset.start) + s"^${head.clusterPrefix}" + replaceClusters(s, rest, head.offset.end) 
  }
  def interpretDictValue(s: String): QueryExpr = Concat(s.split(" ").map(WordToken):_*)
  def parseDict(dicts: Seq[Dictionary]): Map[String, Seq[QueryExpr]] = {
    val dictMap = dicts.map(d => (d.name, d)).toMap
    dictMap.mapValues(d => d.positive.map(interpretDictValue).toSeq)
  }
  def interpret(env: EnvironmentState, parser: String => QueryExpr): Seq[QueryExpr] = {
    val orignalQuery = env.query
    val replaced = replaceClusters(orignalQuery, env.replacements.sortBy(_.offset)) match {
      case s if s.contains("(") && s.contains(")") => s
      case s => s"($s)"
    }
    val parsed = parser(replaced)
    val parsedDict = parseDict(env.dictionaries)
    QueryExpr.evalDicts(parsed, parsedDict)
  }

}