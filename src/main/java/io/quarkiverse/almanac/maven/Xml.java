package io.quarkiverse.almanac.maven;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Namespace-aware DOM helpers for Maven XML (poms, maven-metadata.xml). Elements are matched by local name, so
/// documents with and without the POM namespace are handled alike. DTDs and external entities are rejected.
final class Xml {

    private Xml() {
    }

    /// Parses a document and returns its root element.
    static Element parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("invalid XML: " + e.getMessage(), e);
        }
    }

    /// Direct child elements with the given local name, in document order.
    static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(localName(element))) {
                result.add(element);
            }
        }
        return result;
    }

    /// The element reached by following direct children with the given local names.
    static Optional<Element> path(Element from, String... localNames) {
        Element current = from;
        for (String name : localNames) {
            List<Element> next = children(current, name);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.getFirst();
        }
        return Optional.of(current);
    }

    /// Trimmed text of a direct child element; empty when the child is absent or blank.
    static Optional<String> childText(Element parent, String localName) {
        return path(parent, localName).map(e -> e.getTextContent().strip()).filter(text -> !text.isEmpty());
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }
}
