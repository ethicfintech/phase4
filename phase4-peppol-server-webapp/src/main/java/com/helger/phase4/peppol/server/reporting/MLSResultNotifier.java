/*
 * Copyright (C) 2020-2026 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phase4.peppol.server.reporting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.helger.base.string.StringHelper;
import com.helger.json.JsonObject;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.peppol.server.APConfig;
import com.helger.xml.serialize.read.DOMReader;

/**
 * Forwards a received MLS (Message Level Status) to the ASP application.<br>
 * An MLS names the document it answers by that document's SBDH instance
 * identifier. The application matches it to the Tax Data Document (answer of
 * the tax authority) or to the invoice (answer of the receiving Access Point).
 */
public final class MLSResultNotifier
{
  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (MLSResultNotifier.class);
  private static final String CBC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  private static final String CAC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";

  private MLSResultNotifier ()
  {}

  @Nullable
  private static Element _firstChild (@Nullable final Element aParent, final String sNS, final String sLocalName)
  {
    if (aParent != null)
      for (Node aChild = aParent.getFirstChild (); aChild != null; aChild = aChild.getNextSibling ())
        if (aChild.getNodeType () == Node.ELEMENT_NODE &&
            sNS.equals (aChild.getNamespaceURI ()) &&
            sLocalName.equals (aChild.getLocalName ()))
          return (Element) aChild;
    return null;
  }

  @Nullable
  private static String _text (@Nullable final Element aElement)
  {
    if (aElement == null)
      return null;
    final String s = aElement.getTextContent ();
    return s == null ? null : s.trim ();
  }

  /**
   * Parse the MLS and post it to the ASP application. Never throws.
   *
   * @param aMLSBytes
   *        The serialized ApplicationResponse. May be <code>null</code>.
   * @param sMLSSenderURI
   *        URI encoded participant ID of the MLS sender.
   */
  public static void notifyReceivedMLS (final byte @Nullable [] aMLSBytes, @Nullable final String sMLSSenderURI)
  {
    final String sUrl = APConfig.getWebhookMlsResultUrl ();
    if (StringHelper.hasNoText (sUrl) || aMLSBytes == null)
      return;

    try
    {
      final Document aDoc = DOMReader.readXMLDOM (aMLSBytes);
      if (aDoc == null || aDoc.getDocumentElement () == null)
      {
        LOGGER.warn ("MLS result: the received MLS is not well-formed XML");
        return;
      }
      final Element aRoot = aDoc.getDocumentElement ();
      final Element aDocResponse = _firstChild (aRoot, CAC_NS, "DocumentResponse");
      final String sResponseCode = _text (_firstChild (_firstChild (aDocResponse, CAC_NS, "Response"), CBC_NS, "ResponseCode"));
      final String sReferencedID = _text (_firstChild (_firstChild (aDocResponse, CAC_NS, "DocumentReference"), CBC_NS, "ID"));
      if (StringHelper.hasNoText (sResponseCode) || StringHelper.hasNoText (sReferencedID))
      {
        LOGGER.warn ("MLS result: response code or referenced document ID is missing in the received MLS");
        return;
      }

      // One line per issue: all descriptions and status reasons of the line responses
      final StringBuilder aIssues = new StringBuilder ();
      if (aDocResponse != null)
      {
        final NodeList aLines = aDocResponse.getElementsByTagNameNS (CAC_NS, "LineResponse");
        for (int i = 0; i < aLines.getLength (); ++i)
        {
          final Element aLine = (Element) aLines.item (i);
          final StringBuilder aOne = new StringBuilder ();
          for (final String sName : new String [] { "Description", "StatusReason" })
          {
            final NodeList aTexts = aLine.getElementsByTagNameNS (CBC_NS, sName);
            for (int j = 0; j < aTexts.getLength (); ++j)
            {
              final String sText = _text ((Element) aTexts.item (j));
              if (StringHelper.hasText (sText))
              {
                if (aOne.length () > 0)
                  aOne.append (" - ");
                aOne.append (sText.replace ('\n', ' ').replace ('\r', ' '));
              }
            }
          }
          if (aOne.length () > 0)
          {
            if (aIssues.length () > 0)
              aIssues.append ('\n');
            aIssues.append (aOne);
          }
        }
      }

      final JsonObject aJson = new JsonObject ();
      aJson.add ("referenced_id", sReferencedID);
      aJson.add ("response_code", sResponseCode);
      aJson.add ("issues", aIssues.toString ());
      aJson.add ("mls_sender", StringHelper.getNotNull (sMLSSenderURI));

      final HttpRequest.Builder aBuilder = HttpRequest.newBuilder ()
                                                      .uri (URI.create (sUrl))
                                                      .timeout (Duration.ofSeconds (30))
                                                      .header ("Content-Type", "application/json; charset=utf-8")
                                                      .POST (HttpRequest.BodyPublishers.ofString (aJson.getAsJsonString (),
                                                                                                  StandardCharsets.UTF_8));
      final String sToken = APConfig.getWebhookToken ();
      if (StringHelper.hasText (sToken))
        aBuilder.header ("X-Webhook-Token", sToken);

      final HttpClient aClient = HttpClient.newBuilder ().connectTimeout (Duration.ofSeconds (10)).build ();
      final HttpResponse <String> aResponse = aClient.send (aBuilder.build (), HttpResponse.BodyHandlers.ofString ());
      LOGGER.info ("MLS result (" +
                   sResponseCode +
                   " for document '" +
                   sReferencedID +
                   "') delivered to " +
                   sUrl +
                   " → HTTP " +
                   aResponse.statusCode () +
                   " " +
                   aResponse.body ());
    }
    catch (final Exception ex)
    {
      LOGGER.error ("MLS result: delivery to " + sUrl + " failed", ex);
    }
  }
}
