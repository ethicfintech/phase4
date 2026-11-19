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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.helger.annotation.concurrent.Immutable;
import com.helger.base.string.StringHelper;
import com.helger.json.JsonObject;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.peppol.server.APConfig;

/**
 * Tells the ASP application whether the AE Tax Data Document (TDD) for an
 * invoice was sent to the Tax Authority with a valid AS4 receipt or not. The
 * application uses this to set the "Reported to FTA" status - it must never
 * assume the TDD was sent just because the invoice itself was delivered.
 */
@Immutable
public final class TDDResultNotifier
{
  public static final String DIRECTION_OUTBOUND = "outbound";
  public static final String DIRECTION_INBOUND = "inbound";

  private static final String CBC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (TDDResultNotifier.class);

  private TDDResultNotifier ()
  {}

  @Nullable
  private static String _getDirectChildText (@NonNull final Element aUBLElement, @NonNull final String sLocalName)
  {
    for (Node aChild = aUBLElement.getFirstChild (); aChild != null; aChild = aChild.getNextSibling ())
      if (aChild.getNodeType () == Node.ELEMENT_NODE &&
          CBC_NS.equals (aChild.getNamespaceURI ()) &&
          sLocalName.equals (aChild.getLocalName ()))
      {
        final String sText = aChild.getTextContent ();
        return sText == null ? null : sText.trim ();
      }
    return null;
  }

  /**
   * Post the TDD sending result to the ASP application. Never throws.
   *
   * @param sDirection
   *        {@link #DIRECTION_OUTBOUND} if we sent the invoice,
   *        {@link #DIRECTION_INBOUND} if we received it.
   * @param sParticipantURI
   *        URI encoded participant ID of our client (the invoice sender for
   *        outbound, the invoice receiver for inbound).
   * @param aUBLElement
   *        The Invoice or CreditNote root element the TDD was built from.
   * @param sTransportHeaderID
   *        The SBDH instance identifier used in the TDD.
   * @param bSuccess
   *        <code>true</code> if the TDD was sent and a valid AS4 receipt was
   *        returned.
   * @param sMessage
   *        Details (TDD type on success, error text on failure).
   */
  public static void notifyResult (@NonNull final String sDirection,
                                   @Nullable final String sParticipantURI,
                                   @NonNull final Element aUBLElement,
                                   @Nullable final String sTransportHeaderID,
                                   final boolean bSuccess,
                                   @Nullable final String sMessage)
  {
    notifyResult (sDirection, sParticipantURI, aUBLElement, sTransportHeaderID, bSuccess, sMessage, null, null);
  }

  /**
   * Post the TDD sending result to the ASP application. Never throws.
   *
   * @param sDirection
   *        {@link #DIRECTION_OUTBOUND} or {@link #DIRECTION_INBOUND}
   * @param sParticipantURI
   *        URI encoded participant ID of our client
   * @param aUBLElement
   *        The Invoice or CreditNote root element the TDD was built from.
   * @param sTransportHeaderID
   *        The SBDH instance identifier of the invoice, used in the TDD.
   * @param bSuccess
   *        <code>true</code> if the TDD was sent and a valid AS4 receipt was
   *        returned.
   * @param sMessage
   *        Details (TDD type on success, error text on failure).
   * @param sTDDInstanceID
   *        The SBDH instance identifier of the TDD itself. The MLS of the tax
   *        authority refers to this ID.
   * @param sTDDType
   *        "S" (submit) or "F" (failed document)
   */
  public static void notifyResult (@NonNull final String sDirection,
                                   @Nullable final String sParticipantURI,
                                   @NonNull final Element aUBLElement,
                                   @Nullable final String sTransportHeaderID,
                                   final boolean bSuccess,
                                   @Nullable final String sMessage,
                                   @Nullable final String sTDDInstanceID,
                                   @Nullable final String sTDDType)
  {
    final String sUrl = APConfig.getWebhookTddResultUrl ();
    if (StringHelper.hasNoText (sUrl))
      return;

    try
    {
      final String sInvoiceNo = _getDirectChildText (aUBLElement, "ID");
      String sTypeCode = _getDirectChildText (aUBLElement, "InvoiceTypeCode");
      if (sTypeCode == null)
        sTypeCode = _getDirectChildText (aUBLElement, "CreditNoteTypeCode");

      final JsonObject aJson = new JsonObject ();
      aJson.add ("direction", sDirection);
      aJson.add ("participant", StringHelper.getNotNull (sParticipantURI));
      aJson.add ("invoice_no", StringHelper.getNotNull (sInvoiceNo));
      aJson.add ("type_code", StringHelper.getNotNull (sTypeCode));
      aJson.add ("root_element", StringHelper.getNotNull (aUBLElement.getLocalName ()));
      aJson.add ("transport_header_id", StringHelper.getNotNull (sTransportHeaderID));
      aJson.add ("success", bSuccess);
      aJson.add ("message", StringHelper.getNotNull (sMessage));
      aJson.add ("tdd_instance_id", StringHelper.getNotNull (sTDDInstanceID));
      aJson.add ("tdd_type", StringHelper.getNotNull (sTDDType));

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
      LOGGER.info ("TDD result (" +
                   sDirection +
                   ", invoice '" +
                   sInvoiceNo +
                   "', success=" +
                   bSuccess +
                   ") delivered to " +
                   sUrl +
                   " → HTTP " +
                   aResponse.statusCode () +
                   " " +
                   aResponse.body ());
    }
    catch (final Throwable t)
    {
      if (t instanceof InterruptedException)
        Thread.currentThread ().interrupt ();
      LOGGER.error ("TDD result delivery failed to " + sUrl, t);
    }
  }
}
