package com.tikoncha.darcha.parser

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Creates namespace-aware [XmlPullParser] instances for streaming OOXML parts.
 *
 * The `org.xmlpull.v1` API is not part of the JDK: at runtime Android supplies
 * the implementation, and pure-JVM unit tests get it from kxml2 (which ships the
 * factory-discovery service, so [XmlPullParserFactory.newInstance] resolves it).
 * There are no `android.*` imports here — this module is pure JVM.
 */
internal object Xml {

    /**
     * A namespace-aware parser reading [input]. Encoding is auto-detected from
     * the XML declaration (the `null` encoding hint). A fresh factory is created
     * per call so instances are safe to use from any thread.
     */
    fun newPullParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply { setInput(input, null) }
    }
}
