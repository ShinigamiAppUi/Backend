package streamix.readingcatalog

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts a provider-selected novel body to reader text without flattening paragraph
 * boundaries. The selected element is cloned so extraction never mutates a shared DOM.
 */
internal fun extractReadingNovelText(element: Element): String {
    val body = element.clone()
    val ignoredChrome = "script, style, noscript, nav, aside, footer, form, button, iframe, .ads, .ad, .ad-container, .adsbox, .adsads, .ad-wrapper, .ads-wrapper, .ad-banner, .banner-ad, .advertisement, .advert, .adsbygoogle, .chapter-nav, .chapter-navigation, .chapter-nav-links, .reader-navigation, .chapter-tools, .bottomnav, .pagination, .prev-next, .nextprev, .post-navigation, .entry-footer, .share-buttons, .social-share, .sharedaddy, .jp-relatedposts, .related-posts, .comment-area, .comments-area, #disqus_thread"
    // Jsoup Element.select() only selects descendants; explicitly reject a selected
    // root that is itself an ad/navigation wrapper before extracting its text.
    if (body.`is`(ignoredChrome)) return ""
    body.select(ignoredChrome).remove()
    val output = StringBuilder()
    val blockTags = setOf("article", "section", "div", "p", "blockquote", "li", "ul", "ol", "h1", "h2", "h3", "h4", "br", "pre")

    fun append(node: Node) {
        when (node) {
            is TextNode -> output.append(node.wholeText)
            is Element -> {
                val tag = node.normalName()
                if (tag == "br") {
                    output.append("§§BR§§")
                } else {
                    val block = tag in blockTags
                    if (block) output.append("§§BLOCK§§")
                    node.childNodes().forEach(::append)
                    if (block) output.append("§§BLOCK§§")
                }
            }
            else -> node.childNodes().forEach(::append)
        }
    }

    body.childNodes().forEach(::append)
    return output.toString()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\t\\f ]+"), " ")
        .replace(Regex("\\s*§§BLOCK§§\\s*"), "\n\n")
        .replace(Regex("\\s*§§BR§§\\s*"), "\n")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/**
 * Tries site-specific selectors in declared priority order. A generic or ad-only
 * container must not mask a later selector that contains the actual chapter body.
 */
internal fun extractFirstReadingNovelText(document: org.jsoup.nodes.Document, selectorList: String): String {
    for (selector in selectorList.split(',').map(String::trim).filter(String::isNotEmpty)) {
        val candidates = runCatching { document.select(selector) }.getOrNull() ?: continue
        for (candidate in candidates) {
            val text = extractReadingNovelText(candidate)
            if (text.isNotBlank()) return text
        }
    }
    return ""
}
