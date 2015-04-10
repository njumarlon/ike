package org.allenai.dictionary.ml.subsample

import nl.inl.blacklab.search.lucene.SpanQueryAnd
import nl.inl.blacklab.search.{ Hits, Searcher }
import org.allenai.dictionary._
import org.allenai.dictionary.ml.TokenizedQuery
import org.apache.lucene.search.spans.SpanQuery

/** Sampler that returns sentences that could be matched by a query that is
  * within an edit distance of the given query. See SpanQueryFuzzySequence.
  *
  * @param minEdits minimum edits a sentence can be from the query to be returned
  * @param maxEdits maximum edits a sentence can be from the query to be returned
  */
case class FuzzySequenceSampler(minEdits: Int, maxEdits: Int)
    extends Sampler() {

  require(minEdits >= 0)
  require(maxEdits >= minEdits)

  def buildFuzzySequenceQuery(tokenizedQuery: TokenizedQuery, searcher: Searcher): SpanQuery = {
    val asSpanQueries = tokenizedQuery.getSeq.map(
      q => searcher.createSpanQuery(BlackLabSemantics.blackLabQuery(q))
    )

    // Figure out what subsequence we should record as capture group.
    var onIndex = 0
    var captureList = List[CaptureSpan]()
    tokenizedQuery.nonCaptures.zip(tokenizedQuery.captures).foreach {
      case (leftNonCapture, capture) =>
        onIndex += leftNonCapture.size
        captureList = CaptureSpan(capture.columnName, onIndex,
          onIndex + capture.seq.size) +: captureList
        onIndex += capture.seq.size
    }
    val querySize = tokenizedQuery.getSeq.size
    new SpanQueryFuzzySequence(asSpanQueries, querySize - maxEdits, querySize - minEdits, true,
      searcher.getIndexStructure.alwaysHasClosingToken(), captureList)
  }

  override def getSample(qexpr: QExpr, searcher: Searcher, table: Table): Hits = {
    val tokenizedQuery = TokenizedQuery.buildFromQuery(qexpr, table.cols)
    searcher.find(buildFuzzySequenceQuery(tokenizedQuery, searcher))
  }

  override def getLabelledSample(qexpr: QExpr, searcher: Searcher, table: Table): Hits = {
    val tokenizedQuery = TokenizedQuery.buildFromQuery(QueryLanguage.nameCaptureGroups(
      qexpr,
      table.cols
    ))
    val sequenceQuery = buildFuzzySequenceQuery(tokenizedQuery, searcher)
    val labelledSpans = searcher.createSpanQuery(
      BlackLabSemantics.blackLabQuery(
        Sampler.getLabelledExampleQuery(tokenizedQuery, table)
      )
    )
    searcher.find(new SpanQueryAnd(sequenceQuery, labelledSpans))
  }
}
