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
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Type;

import com.helger.annotation.Nonempty;
import com.helger.base.string.StringHelper;
import com.helger.json.JsonObject;
import com.helger.mime.CMimeType;
import com.helger.peppol.servicedomain.EPeppolNetwork;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.peppol.Phase4PeppolSender;
import com.helger.photon.api.IAPIDescriptor;
import com.helger.servlet.response.UnifiedResponse;
import com.helger.web.scope.IRequestWebScopeWithoutResponse;

/**
 * Tell whether a participant is registered in the Peppol network (SML).<br>
 * The answer is one of three, returned as JSON
 * <code>{"participant":"...","status":"registered|not_registered|error","message":"..."}</code>:
 * <ul>
 * <li><code>registered</code> - a NAPTR record exists for the participant</li>
 * <li><code>not_registered</code> - the DNS answered that the participant does
 * not exist</li>
 * <li><code>error</code> - the DNS could not be asked (timeout, server
 * failure). This is NOT "not registered": ask again later.</li>
 * </ul>
 */
public final class APIGetLookupParticipant extends AbstractVerifyingAPIExecutor
{
  public static final String PARAM_PARTICIPANT_ID = "participantId";

  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (APIGetLookupParticipant.class);

  private final EPeppolNetwork m_eStage;

  public APIGetLookupParticipant (@NonNull final EPeppolNetwork eStage)
  {
    m_eStage = eStage;
  }

  @Override
  protected void verifiedInvokeAPI (@NonNull final IAPIDescriptor aAPIDescriptor,
                                    @NonNull @Nonempty final String sPath,
                                    @NonNull final Map <String, String> aPathVariables,
                                    @NonNull final IRequestWebScopeWithoutResponse aRequestScope,
                                    @NonNull final UnifiedResponse aUnifiedResponse) throws Exception
  {
    final String sParticipantID = aPathVariables.get (PARAM_PARTICIPANT_ID);
    if (StringHelper.isEmpty (sParticipantID))
      throw new APIParamException ("API call retrieved an empty participant ID");

    String sStatus = "error";
    String sMessage;
    try
    {
      final IParticipantIdentifier aParticipantID = PeppolIdentifierFactory.INSTANCE.parseParticipantIdentifier (sParticipantID);
      if (aParticipantID == null)
        throw new APIParamException ("The participant ID '" + sParticipantID + "' is not valid");

      final String sDNSName = Phase4PeppolSender.URL_PROVIDER.getDNSNameOfParticipant (aParticipantID,
                                                                                      m_eStage.getSMLInfo ());
      // Absolute name, so that no DNS search suffix is appended
      final Lookup aLookup = new Lookup (sDNSName.endsWith (".") ? sDNSName : sDNSName + ".", Type.NAPTR);
      aLookup.run ();
      final int nResult = aLookup.getResult ();
      if (nResult == Lookup.SUCCESSFUL)
      {
        sStatus = "registered";
        sMessage = "NAPTR record found for " + sDNSName;
      }
      else
        if (nResult == Lookup.HOST_NOT_FOUND || nResult == Lookup.TYPE_NOT_FOUND)
        {
          sStatus = "not_registered";
          sMessage = "No NAPTR record for " + sDNSName + " (" + aLookup.getErrorString () + ")";
        }
        else
        {
          sMessage = "DNS lookup of " + sDNSName + " failed: " + aLookup.getErrorString ();
        }
    }
    catch (final APIParamException ex)
    {
      throw ex;
    }
    catch (final Exception ex)
    {
      sMessage = "Lookup failed: " + (ex.getMessage () != null ? ex.getMessage () : ex.getClass ().getName ());
    }
    LOGGER.info ("Participant lookup '" + sParticipantID + "': " + sStatus + " - " + sMessage);

    final JsonObject aJson = new JsonObject ();
    aJson.add ("participant", sParticipantID);
    aJson.add ("status", sStatus);
    aJson.add ("message", sMessage);

    aUnifiedResponse.setContentAndCharset (aJson.getAsJsonString (), StandardCharsets.UTF_8)
                    .setMimeType (CMimeType.APPLICATION_JSON)
                    .disableCaching ();
  }
}
