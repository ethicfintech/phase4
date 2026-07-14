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
package com.helger.phase4.peppol.server.spi;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.unece.cefact.namespaces.sbdh.StandardBusinessDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.helger.annotation.Nonempty;
import com.helger.annotation.style.IsSPIImplementation;
import com.helger.base.string.StringHelper;
import com.helger.base.string.StringParser;
import com.helger.collection.CollectionFind;
import com.helger.http.CHttp;
import com.helger.http.header.HttpHeaderMap;
import com.helger.io.file.SimpleFileIO;
import com.helger.peppol.reporting.api.PeppolReportingItem;
import com.helger.peppol.reporting.api.backend.PeppolReportingBackend;
import com.helger.peppol.reporting.api.backend.PeppolReportingBackendException;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.security.PeppolTrustedCA;
import com.helger.peppol.servicedomain.EPeppolNetwork;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.peppolid.peppol.pidscheme.EPredefinedParticipantIdentifierScheme;
import com.helger.phase4.CAS4;
import com.helger.phase4.config.AS4Configuration;
import com.helger.phase4.ebms3header.Ebms3Property;
import com.helger.phase4.ebms3header.Ebms3UserMessage;
import com.helger.phase4.error.AS4ErrorList;
import com.helger.phase4.incoming.IAS4IncomingMessageMetadata;
import com.helger.phase4.incoming.IAS4IncomingMessageState;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.messaging.EAS4MessageMode;
import com.helger.phase4.model.error.EEbmsError;
import com.helger.phase4.peppol.Phase4PeppolSender;
import com.helger.phase4.peppol.server.APConfig;
import com.helger.phase4.peppol.server.storage.StorageHelper;
import com.helger.phase4.peppol.servlet.IPhase4PeppolIncomingSBDHandlerSPI;
import com.helger.phase4.peppol.servlet.Phase4PeppolServletMessageProcessorSPI;
import com.helger.phase4.profile.peppol.Phase4PeppolHttpClientSettings;
import com.helger.phase4.sender.EAS4UserMessageSendResult;
import com.helger.phase4.util.Phase4IncomingException;
import com.helger.photon.io.PhotonWorkerPool;
import com.helger.security.certificate.CertificateHelper;
import com.helger.security.certificate.TrustedCAChecker;
import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.diver.api.coord.DVRCoordinate;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.phive.peppol.PeppolValidationPintAE;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.xml.serialize.read.DOMReader;

/**
 * Logging implementation of {@link IPhase4PeppolIncomingSBDHandlerSPI}.
 *
 * @author Philip Helger
 */
@IsSPIImplementation
public class StoringPeppolIncomingSBDHandlerSPI implements IPhase4PeppolIncomingSBDHandlerSPI
{
  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (StoringPeppolIncomingSBDHandlerSPI.class);

  private static final ValidationExecutorSetRegistry <IValidationSourceXML> VES_REGISTRY = _createVESRegistry ();

  private static ValidationExecutorSetRegistry <IValidationSourceXML> _createVESRegistry ()
  {
    final ValidationExecutorSetRegistry <IValidationSourceXML> aRegistry = new ValidationExecutorSetRegistry <> ();
    PeppolValidationPintAE.init (aRegistry);
    return aRegistry;
  }

  @NonNull
  private static List <String> _getValidationErrors (@NonNull final Element aUBLElement,
                                                      @NonNull final String sDocTypeURI)
  {
    // Only validate AE PINT documents
    if (!sDocTypeURI.contains ("@ae-1"))
      return Collections.emptyList ();

    final String sLocalName = aUBLElement.getLocalName ();
    final DVRCoordinate aVESID;
    if ("Invoice".equals (sLocalName))
      aVESID = PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_INVOICE_2026_05;
    else
      if ("CreditNote".equals (sLocalName))
        aVESID = PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_CREDIT_NOTE_2026_05;
      else
        return Collections.emptyList ();

    final IValidationExecutorSet <IValidationSourceXML> aVES = VES_REGISTRY.getOfID (aVESID);
    if (aVES == null)
    {
      LOGGER.warn ("VES not found in registry for VESID: " + aVESID.getAsSingleID ());
      return Collections.emptyList ();
    }

    final IValidationSourceXML aSource = ValidationSourceXML.create (null, aUBLElement);
    final ValidationResultList aResultList = ValidationExecutionManager.executeValidation (IValidityDeterminator.createDefault (),
                                                                                           aVES,
                                                                                           aSource,
                                                                                           Locale.US);

    final List <String> aErrors = new ArrayList <> ();
    for (final ValidationResult aResult : aResultList)
      for (final IError aError : aResult.getErrorList ())
        if (aError.getErrorLevel ().isGE (EErrorLevel.ERROR))
          aErrors.add (aError.getErrorText (Locale.US));
    return aErrors;
  }

  public void handleIncomingSBD (@NonNull final IAS4IncomingMessageMetadata aMessageMetadata,
                                 @NonNull final HttpHeaderMap aHeaders,
                                 @NonNull final Ebms3UserMessage aUserMessage,
                                 final byte @NonNull [] aSBDBytes,
                                 @NonNull final StandardBusinessDocument aSBD,
                                 @NonNull final PeppolSBDHData aPeppolSBD,
                                 @NonNull final IAS4IncomingMessageState aIncomingState,
                                 @NonNull final AS4ErrorList aProcessingErrorMessages) throws Exception
  {
    final String sMyPeppolSeatID = APConfig.getMyPeppolSeatID ();

    // Example code snippets how to get data
    LOGGER.info ("Received a new Peppol Message");
    LOGGER.info ("  C1 = " + aPeppolSBD.getSenderURIEncoded ());
    LOGGER.info ("  C2 = " + CertificateHelper.getSubjectCN (aIncomingState.getSigningCertificate ()));
    LOGGER.info ("  C3 = " + sMyPeppolSeatID);
    LOGGER.info ("  C4 = " + aPeppolSBD.getReceiverURIEncoded ());
    LOGGER.info ("  DocType = " + aPeppolSBD.getDocumentTypeURIEncoded ());
    LOGGER.info ("  Process = " + aPeppolSBD.getProcessURIEncoded ());
    LOGGER.info ("  CountryC1 = " + aPeppolSBD.getCountryC1 ());

    final IParticipantIdentifier aMLSReceiver;
    if (aPeppolSBD.hasMLSToValue ())
    {
      // Explicit MLS_TO provided
      aMLSReceiver = PeppolIdentifierFactory.INSTANCE.createParticipantIdentifier (aPeppolSBD.getMLSToScheme (),
                                                                                   aPeppolSBD.getMLSToValue ());
    }
    else
    {
      // Always 0242
      final String sScheme = EPredefinedParticipantIdentifierScheme.SPIS.getISO6523Code ();
      // Take from C2 SeatID
      final String sC2SeatID = CertificateHelper.getSubjectCN (aIncomingState.getSigningCertificate ());
      aMLSReceiver = PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme (sScheme +
                                                                                                    ":" +
                                                                                                    sC2SeatID.substring (3));
    }
    if (aMLSReceiver != null)
      LOGGER.info ("  MLS Receiver = " + aMLSReceiver.getURIEncoded ());

    // Example got that stores the data to disk
    final File aFile = StorageHelper.getStorageFile (aMessageMetadata, ".sbd");
    LOGGER.info ("Now writing SBD to '" + aFile.getAbsolutePath () + "' (" + aSBDBytes.length + " bytes)");

    if (SimpleFileIO.writeFile (aFile, aSBDBytes).isFailure ())
      throw new IllegalStateException ("Failed to write SBD to '" +
                                       aFile.getAbsolutePath () +
                                       "' (" +
                                       aSBDBytes.length +
                                       " bytes)");
    LOGGER.info ("Successfully wrote SBD to '" + aFile.getAbsolutePath () + "'");

    // Send MLS (Message Level Status) response back to the sender's AP
    if (aMLSReceiver != null)
    {
      // Extract UBL element from SBD bytes for Schematron validation
      List <String> aValidationErrors = Collections.emptyList ();
      final Document aSBDDoc = DOMReader.readXMLDOM (aSBDBytes);
      if (aSBDDoc != null)
      {
        Element aUBLElement = null;
        for (Node aChild = aSBDDoc.getDocumentElement ().getFirstChild (); aChild != null; aChild = aChild.getNextSibling ())
          if (aChild.getNodeType () == Node.ELEMENT_NODE &&
              !"StandardBusinessDocumentHeader".equals (aChild.getLocalName ()))
          {
            aUBLElement = (Element) aChild;
            break;
          }
        if (aUBLElement != null)
          aValidationErrors = _getValidationErrors (aUBLElement, aPeppolSBD.getDocumentTypeURIEncoded ());
      }
      LOGGER.info ("Validation result: " + aValidationErrors.size () + " error(s) found");

      final IParticipantIdentifier aFinalMLSReceiver = aMLSReceiver;
      final String sInstanceID = aSBD.getStandardBusinessDocumentHeader ()
                                     .getDocumentIdentification ()
                                     .getInstanceIdentifier ();
      final List <String> aFinalErrors = aValidationErrors;
      PhotonWorkerPool.getInstance ()
                      .run (CAS4.LIB_NAME + " Send Peppol MLS response",
                            () -> {
                              try
                              {
                                _sendMLS (aPeppolSBD, aFinalMLSReceiver, sInstanceID, aFinalErrors);
                              }
                              catch (final Exception ex)
                              {
                                LOGGER.error ("Failed to send MLS response to " +
                                              aFinalMLSReceiver.getURIEncoded (),
                                              ex);
                              }
                            });
    }

    {
      // TODO This is only demo code to force an error
      // Check if any "MessageProperty" with name "MockAction" is contained
      final Ebms3Property aMockAction = CollectionFind.findFirst (aUserMessage.getMessageProperties ().getProperty (),
                                                                  x -> "MockAction".equals (x.getName ()));
      if (aMockAction != null)
      {
        // Explicitly return an Error - for testing errors
        LOGGER.info ("Found MockAction to return AS4 Error with value '" + aMockAction.getValue () + "'");
        aProcessingErrorMessages.add (EEbmsError.EBMS_OTHER.errorBuilder (Locale.US)
                                                           .errorCode ("MockAction")
                                                           .errorDetail ("Mock error: " + aMockAction.getValue ())
                                                           .build ());
      }
    }

    {
      // TODO This is only demo code to force an error
      // Check if any "MessageProperty" with name "MockErrorStatusCode" is contained
      final Ebms3Property aMockError = CollectionFind.findFirst (aUserMessage.getMessageProperties ().getProperty (),
                                                                 x -> "MockErrorStatusCode".equals (x.getName ()));
      if (aMockError != null)
      {
        // Explicitly return an Error - for testing errors
        LOGGER.info ("Found MockErrorStatusCode to return error with value '" + aMockError.getValue () + "'");
        // Default to HTTP 400
        final int nStatusCode = StringParser.parseInt (aMockError.getValue (), CHttp.HTTP_BAD_REQUEST);
        throw new Phase4IncomingException ("Mock error " + aMockError.getValue ()).setHttpStatusCode (nStatusCode);
      }
    }

    // Last action in this method
    PhotonWorkerPool.getInstance ().run (CAS4.LIB_NAME + " Handle Peppol Reporting for Peppol incoming message", () -> {
      // TODO If you have a way to determine the real end user
      // of the message here, this might be a good opportunity
      // to store the data for Peppol Reporting (do this
      // asynchronously as the last activity)
      // Note: this is a separate thread so that it does not
      // block the sending of the positive receipt message

      // TODO Peppol Reporting - enable if possible to be done
      // in here
      if (false)
        try
        {
          LOGGER.info ("Creating Peppol Reporting Item and storing it");

          // TODO determine correct values for Peppol Reporting for the next three fields
          final String sC3ID = sMyPeppolSeatID;
          final String sC4CountryCode = "AT";
          final String sEndUserID = "EndUserID";

          // Create the reporting item
          final PeppolReportingItem aReportingItem = Phase4PeppolServletMessageProcessorSPI.createPeppolReportingItemForReceivedMessage (aUserMessage,
                                                                                                                                         aPeppolSBD,
                                                                                                                                         aIncomingState,
                                                                                                                                         sC3ID,
                                                                                                                                         sC4CountryCode,
                                                                                                                                         sEndUserID);
          PeppolReportingBackend.withBackendDo (AS4Configuration.getConfig (),
                                                aBackend -> aBackend.storeReportingItem (aReportingItem));
        }
        catch (final PeppolReportingBackendException ex)
        {
          LOGGER.error ("Failed to store Peppol Reporting Item", ex);
          // TODO improve Peppol Reporting error handling
        }
    });
  }

  private static String _escapeXML (@NonNull final String s)
  {
    return s.replace ("&", "&amp;").replace ("<", "&lt;").replace (">", "&gt;");
  }

  private static void _sendMLS (@NonNull final PeppolSBDHData aPeppolSBD,
                                 @NonNull final IParticipantIdentifier aMLSReceiver,
                                 @Nullable final String sOriginalInstanceID,
                                 @NonNull final List <String> aValidationErrors) throws Exception
  {
    final String sSeatID = APConfig.getMyPeppolSeatID ();
    final IParticipantIdentifier aSenderID = PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme ("0235:" +
                                                                                                                            sSeatID);

    // Build PEPPOL eDEC MLS (Message Level Status) ApplicationResponse
    final String sDocRefID = sOriginalInstanceID != null ? sOriginalInstanceID : UUID.randomUUID ().toString ();
    final String sIssueTime = OffsetTime.now (ZoneOffset.UTC).truncatedTo (ChronoUnit.SECONDS).toString ();

    // Receiver: value is like "0242:000005-testbed.3001" — split into schemeID + ID
    final String sMLSReceiverValue = aMLSReceiver.getValue ();
    final int nColonIdx = sMLSReceiverValue.indexOf (':');
    final String sMLSReceiverSchemeID = nColonIdx > 0 ? sMLSReceiverValue.substring (0, nColonIdx) : "0242";
    final String sMLSReceiverID = nColonIdx > 0 ? sMLSReceiverValue.substring (nColonIdx + 1) : sMLSReceiverValue;

    // ResponseCode: AP (accepted) or RE (rejected with errors)
    final String sResponseCode = aValidationErrors.isEmpty () ? "AP" : "RE";

    // Build LineResponse elements for each BV error
    final StringBuilder sbLineResponses = new StringBuilder ();
    for (final String sError : aValidationErrors)
      sbLineResponses.append ("<cac:LineResponse>")
                     .append ("<cac:LineReference><cbc:LineID>/</cbc:LineID></cac:LineReference>")
                     .append ("<cac:Response>")
                     .append ("<cbc:ResponseCode>BV</cbc:ResponseCode>")
                     .append ("<cbc:Description>")
                     .append (_escapeXML (sError))
                     .append ("</cbc:Description>")
                     .append ("</cac:Response>")
                     .append ("</cac:LineResponse>");

    final String sResponseXML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                "<ApplicationResponse xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2\"" +
                                " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"" +
                                " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">" +
                                "<cbc:CustomizationID>urn:peppol:edec:mls:1.0</cbc:CustomizationID>" +
                                "<cbc:ProfileID>urn:peppol:edec:mls</cbc:ProfileID>" +
                                "<cbc:ID>mls-" +
                                UUID.randomUUID () +
                                "</cbc:ID>" +
                                "<cbc:IssueDate>" +
                                LocalDate.now (ZoneOffset.UTC) +
                                "</cbc:IssueDate>" +
                                "<cbc:IssueTime>" +
                                sIssueTime +
                                "</cbc:IssueTime>" +
                                "<cac:SenderParty><cbc:EndpointID schemeID=\"0242\">" +
                                sSeatID.substring (3) +
                                "</cbc:EndpointID></cac:SenderParty>" +
                                "<cac:ReceiverParty><cbc:EndpointID schemeID=\"" +
                                sMLSReceiverSchemeID +
                                "\">" +
                                sMLSReceiverID +
                                "</cbc:EndpointID></cac:ReceiverParty>" +
                                "<cac:DocumentResponse>" +
                                "<cac:Response><cbc:ResponseCode>" +
                                sResponseCode +
                                "</cbc:ResponseCode></cac:Response>" +
                                "<cac:DocumentReference><cbc:ID>" +
                                sDocRefID +
                                "</cbc:ID></cac:DocumentReference>" +
                                sbLineResponses +
                                "</cac:DocumentResponse>" +
                                "</ApplicationResponse>";

    final IDocumentTypeIdentifier aDocTypeID = PeppolIdentifierFactory.INSTANCE.createDocumentTypeIdentifierWithDefaultScheme ("urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2::ApplicationResponse##urn:peppol:edec:mls:1.0::2.1");
    final IProcessIdentifier aProcessID = PeppolIdentifierFactory.INSTANCE.createProcessIdentifierWithDefaultScheme ("urn:peppol:edec:mls");

    final EPeppolNetwork eStage = APConfig.getPeppolStage ();
    final TrustedCAChecker aCAChecker = eStage.isTest () ? PeppolTrustedCA.peppolTestAP ()
                                                         : PeppolTrustedCA.peppolProductionAP ();

    final SMPClientReadOnly aSMPClient = new SMPClientReadOnly (Phase4PeppolSender.URL_PROVIDER,
                                                                aMLSReceiver,
                                                                eStage.getSMLInfo ());
    APConfig.applyHttpProxySettings (aSMPClient.httpClientSettings ());

    final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
    APConfig.applyHttpProxySettings (aHCS);

    final Element aPayload = DOMReader.readXMLDOM (sResponseXML.getBytes (StandardCharsets.UTF_8))
                                      .getDocumentElement ();

    final EAS4UserMessageSendResult eResult = Phase4PeppolSender.builder ()
                                                                .httpClientFactory (aHCS)
                                                                .documentTypeID (aDocTypeID)
                                                                .processID (aProcessID)
                                                                .senderParticipantID (aSenderID)
                                                                .receiverParticipantID (aMLSReceiver)
                                                                .senderPartyID (sSeatID)
                                                                .countryC1 (APConfig.getMyPeppolCountryCode ())
                                                                .payload (aPayload)
                                                                .peppolAP_CAChecker (aCAChecker)
                                                                .smpClient (aSMPClient)
                                                                .sendMessageAndCheckForReceipt ();
    LOGGER.info ("MLS send result to " + aMLSReceiver.getURIEncoded () + ": " + eResult);
  }

  public void processAS4ResponseMessage (@NonNull final IAS4IncomingMessageMetadata aIncomingMessageMetadata,
                                         @NonNull final IAS4IncomingMessageState aIncomingState,
                                         @NonNull @Nonempty final String sResponseMessageID,
                                         final byte @Nullable [] aResponseBytes,
                                         final boolean bResponsePayloadIsAvailable,
                                         @NonNull final AS4ErrorList aEbmsErrorMessages)
  {
    final boolean bIsErrorMessage = aEbmsErrorMessages.isNotEmpty ();
    if (aIncomingMessageMetadata.getMode () == EAS4MessageMode.REQUEST)
      LOGGER.info ("AS4 " + (bIsErrorMessage ? "error" : "success") + " response on an inbound message");
    else
      LOGGER.info ("AS4 " + (bIsErrorMessage ? "error" : "success") + " response on an outbound message");

    if (bIsErrorMessage)
    {
      LOGGER.error ("  Response has " +
                    aEbmsErrorMessages.size () +
                    " errors: " +
                    aEbmsErrorMessages.getAllMapped (x -> StringHelper.getConcatenatedOnDemand (x.getEbmsError ()
                                                                                                 .getDescriptionValue (),
                                                                                                " / ",
                                                                                                x.getEbmsError ()
                                                                                                 .getErrorDetail ())));
    }

    if (bResponsePayloadIsAvailable)
      LOGGER.info ("  Response content: " + new String (aResponseBytes, StandardCharsets.UTF_8));
  }
}
