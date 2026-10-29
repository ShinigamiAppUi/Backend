package com.baseprovider.streamix

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

interface StreamixHtml {
    fun parse(html: String, baseUrl: String? = null): Document
    fun select(document: Document, selector: String): List<Element>
}

object JsoupStreamixHtml : StreamixHtml {
    override fun parse(html: String, baseUrl: String?): Document =
        if (baseUrl.isNullOrBlank()) org.jsoup.Jsoup.parse(html)
        else org.jsoup.Jsoup.parse(html, baseUrl)

    override fun select(document: Document, selector: String): List<Element> =
        document.select(selector)
}
