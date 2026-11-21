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
package com.helger.phase4.peppol.server.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.w3c.dom.Document;

import com.helger.annotation.Nonempty;
import com.helger.base.array.ArrayHelper;
import com.helger.base.io.stream.StreamHelper;
import com.helger.base.string.StringHelper;
import com.helger.json.JsonObject;
import com.helger.mime.CMimeType;
import com.helger.peppol.uae.tdd.codelist.EUAETDDDocumentTypeCode;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.peppol.server.spi.StoringPeppolIncomingSBDHandlerSPI;
import com.helger.photon.api.IAPIDescriptor;
import com.helger.servlet.response.UnifiedResponse;
import com.helger.web.scope.IRequestWebScopeWithoutResponse;
import com.helger.xml.serialize.read.DOMReader;

/**
 * Build the AE Tax Data Document for the posted invoice or credit note again
 * and send it to the tax authority. Called by the ASP application to send a
 * TDD again that was not delivered or not answered, to resubmit a corrected
 * one, and to withdraw a report. The last path element is the TDD type:
 * <code>S</code> submit, <code>R</code> resubmit, <code>W</code> withdraw
 * (outbound only), <code>F</code> failed (inbound only); the former values
 * <code>false</code> (= S) and <code>true</code> (= F) are still accepted. The
 * result is returned synchronously as JSON:
 * <code>{"success":true|false,"tdd_type":"S"|"R"|"W"|"F","tdd_instance_id":"...","message":"..."}</code>
 */
public final class APIPostSendTDD extends AbstractVerifyingAPIExecutor
{
  public static final String PARAM_DIRECTION = "direction";
  public static final String PARAM_TRANSPORT_HEADER_ID = "transportHeaderId";
  public static final String PARAM_TDD_INSTANCE_ID = "tddInstanceId";
  public static final String PARAM_FAILED = "failed";

  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (APIPostSendTDD.class);

  @Override
  protected void verifiedInvokeAPI (@NonNull final IAPIDescriptor aAPIDescriptor,
                                    @NonNull @Nonempty final String sPath,
                                    @NonNull final Map <String, String> aPathVariables,
                                    @NonNull final IRequestWebScopeWithoutResponse aRequestScope,
                                    @NonNull final UnifiedResponse aUnifiedResponse) throws Exception
  {
    final String sDirection = aPathVariables.get (PARAM_DIRECTION);
    final String sTransportHeaderID = aPathVariables.get (PARAM_TRANSPORT_HEADER_ID);
    final String sTDDInstanceID = aPathVariables.get (PARAM_TDD_INSTANCE_ID);
    final String sTypeParam = aPathVariables.get (PARAM_FAILED);
    final boolean bFailed = "true".equals (sTypeParam) || "F".equals (sTypeParam);
    final boolean bResubmit = "R".equals (sTypeParam);
    final boolean bWithdraw = "W".equals (sTypeParam);
    final byte [] aPayloadBytes = StreamHelper.getAllBytes (aRequestScope.getRequest ().getInputStream ());

    if (!"outbound".equals (sDirection) && !"inbound".equals (sDirection))
      throw new APIParamException ("The direction must be 'outbound' or 'inbound'");
    if (bWithdraw && !"outbound".equals (sDirection))
      throw new APIParamException ("A Withdraw TDD can only be sent for an outbound document");
    if (bFailed && !"inbound".equals (sDirection))
      throw new APIParamException ("A FAILED TDD can only be sent for an inbound document");
    if (StringHelper.isEmpty (sTransportHeaderID))
      throw new APIParamException ("API call retrieved an empty transport header ID");
    if (StringHelper.isEmpty (sTDDInstanceID))
      throw new APIParamException ("API call retrieved an empty TDD instance ID");
    if (ArrayHelper.isEmpty (aPayloadBytes))
      throw new APIParamException ("API call retrieved an empty payload");

    final Document aDoc = DOMReader.readXMLDOM (aPayloadBytes);
    if (aDoc == null || aDoc.getDocumentElement () == null)
      throw new APIParamException ("The payload is not well-formed XML");

    LOGGER.info ("Re-sending AE TDD (" +
                 sDirection +
                 ", type " +
                 (bFailed ? "F" : bWithdraw ? "W" : bResubmit ? "R" : "S") +
                 ") for transport header '" +
                 sTransportHeaderID +
                 "' with TDD instance ID '" +
                 sTDDInstanceID +
                 "'");

    boolean bSuccess = false;
    String sType = bFailed ? "F" : bWithdraw ? "W" : bResubmit ? "R" : "S";
    String sMessage;
    try
    {
      if ("outbound".equals (sDirection))
      {
        final EUAETDDDocumentTypeCode eType = bWithdraw ? EUAETDDDocumentTypeCode.WITHDRAW
                                                        : bResubmit ? EUAETDDDocumentTypeCode.RESUBMIT
                                                                    : EUAETDDDocumentTypeCode.SUBMIT;
        PeppolSender.sendOutboundTDD (aDoc, sTransportHeaderID, sTDDInstanceID, eType);
      }
      else
      {
        final EUAETDDDocumentTypeCode eType = StoringPeppolIncomingSBDHandlerSPI.sendInboundTDD (aDoc.getDocumentElement (),
                                                                                                 sTransportHeaderID,
                                                                                                 bFailed,
                                                                                                 sTDDInstanceID,
                                                                                                 bResubmit);
        sType = eType.getID ();
      }
      bSuccess = true;
      sMessage = "TDD re-sent";
    }
    catch (final Throwable t)
    {
      LOGGER.error ("Re-sending the AE TDD failed", t);
      sMessage = t.getMessage () != null ? t.getMessage () : t.getClass ().getName ();
    }

    final JsonObject aJson = new JsonObject ();
    aJson.add ("success", bSuccess);
    aJson.add ("tdd_type", sType);
    aJson.add ("tdd_instance_id", sTDDInstanceID);
    aJson.add ("message", sMessage);

    aUnifiedResponse.setContentAndCharset (aJson.getAsJsonString (), StandardCharsets.UTF_8)
                    .setMimeType (CMimeType.APPLICATION_JSON)
                    .disableCaching ();
  }
}
