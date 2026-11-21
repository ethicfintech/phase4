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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import com.helger.base.wrapper.Wrapper;
import com.helger.collection.CollectionFind;
import com.helger.http.CHttp;
import com.helger.http.header.HttpHeaderMap;
import com.helger.io.file.SimpleFileIO;
import com.helger.peppol.reporting.api.PeppolReportingItem;
import com.helger.peppol.reporting.api.backend.PeppolReportingBackend;
import com.helger.peppol.reporting.api.backend.PeppolReportingBackendException;
import com.helger.peppol.sbdh.EPeppolMLSType;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.security.PeppolTrustedCA;
import com.helger.peppol.servicedomain.EPeppolNetwork;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.peppolid.peppol.pidscheme.EPredefinedParticipantIdentifierScheme;
import com.helger.peppolid.simple.participant.SimpleParticipantIdentifier;
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
import com.helger.phase4.peppol.server.api.PeppolSender;
import com.helger.phase4.peppol.server.APConfig;
import com.helger.phase4.peppol.server.reporting.MLSResultNotifier;
import com.helger.phase4.peppol.server.reporting.TDDPartyNames;
import com.helger.phase4.peppol.server.reporting.TDDValidation;
import com.helger.phase4.peppol.server.reporting.TDDResultNotifier;
import com.helger.phase4.peppol.server.storage.StorageHelper;
import com.helger.phase4.peppol.servlet.IPhase4PeppolIncomingSBDHandlerSPI;
import com.helger.phase4.peppol.servlet.Phase4PeppolServletMessageProcessorSPI;
import com.helger.phase4.profile.peppol.Phase4PeppolHttpClientSettings;
import com.helger.phase4.sender.EAS4UserMessageSendResult;
import com.helger.phase4.util.Phase4Exception;
import com.helger.phase4.util.Phase4IncomingException;
import com.helger.photon.io.PhotonWorkerPool;
import com.helger.security.certificate.CertificateHelper;
import com.helger.security.certificate.TrustedCAChecker;
import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.diver.api.coord.DVRCoordinate;
import com.helger.phive.api.EValidationBaseType;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.peppol.uae.tdd.PeppolUAETDD10Builder;
import com.helger.peppol.uae.tdd.PeppolUAETDD10ReportedTransactionBuilder;
import com.helger.peppol.uae.tdd.codelist.EUAETDDDocumentScope;
import com.helger.peppol.uae.tdd.codelist.EUAETDDDocumentTypeCode;
import com.helger.peppol.uae.tdd.codelist.EUAETDDReporterRole;
import com.helger.peppol.uae.tdd.jaxb.PeppolUAETDD10Marshaller;
import com.helger.peppol.uae.tdd.v100.TaxDataType;
import com.helger.phive.peppol.PeppolValidationPintAE;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.ubl21.UBL21Marshaller;
import com.helger.xml.serialize.read.DOMReader;
import com.helger.xml.serialize.write.XMLWriter;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

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

  /**
   * The single issue reported when a PINT-AE document could not be validated at
   * all. Such a document is rejected (MLS "RE"): accepting a document that was
   * never checked would be worse than asking the sender to send it again.
   */
  @NonNull
  private static List <String []> _notValidated (@NonNull final String sReason)
  {
    LOGGER.error ("PINT-AE validation could not be performed: " + sReason + " - the document is rejected");
    final List <String []> ret = new ArrayList <> ();
    ret.add (new String [] { "BV", "The document could not be validated by the receiving Access Point: " + sReason });
    return ret;
  }

  /**
   * @param aUBLBytes
   *        The business document, serialized once on the request thread. May be
   *        <code>null</code> if it could not be extracted.
   * @param sDocTypeURI
   *        Peppol document type identifier
   * @return The list of validation errors as {issueType ("SV" or "BV"), errorText} pairs. Empty
   *         means "valid".
   */
  @NonNull
  private static List <String []> _getValidationErrors (final byte @Nullable [] aUBLBytes,
                                                         @NonNull final String sDocTypeURI)
  {
    // Only validate AE PINT documents
    if (!sDocTypeURI.contains ("@ae-1"))
      return Collections.<String []> emptyList ();

    if (aUBLBytes == null)
      return _notValidated ("the business document could not be read from the message");

    // Parse into a DOM that belongs to this thread only
    final Document aUBLDoc = DOMReader.readXMLDOM (aUBLBytes);
    if (aUBLDoc == null || aUBLDoc.getDocumentElement () == null)
      return _notValidated ("the business document is not well-formed XML");

    final boolean bIsSelfBilling = sDocTypeURI.contains ("selfbilling");
    final String sLocalName = aUBLDoc.getDocumentElement ().getLocalName ();
    final DVRCoordinate aVESID;
    if ("Invoice".equals (sLocalName))
      aVESID = bIsSelfBilling ? PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_SB_INVOICE_2026_05
                              : PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_INVOICE_2026_05;
    else
      if ("CreditNote".equals (sLocalName))
        aVESID = bIsSelfBilling ? PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_SB_CREDIT_NOTE_2026_05
                                : PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_CREDIT_NOTE_2026_05;
      else
        return _notValidated ("the root element '" + sLocalName + "' is neither Invoice nor CreditNote");

    final IValidationExecutorSet <IValidationSourceXML> aVES = VES_REGISTRY.getOfID (aVESID);
    if (aVES == null)
      return _notValidated ("the rule set " + aVESID.getAsSingleID () + " is not available");

    final IValidationSourceXML aSource = ValidationSourceXML.create (null, aUBLDoc);
    final ValidationResultList aResultList = ValidationExecutionManager.executeValidation (IValidityDeterminator.createDefault (),
                                                                                           aVES,
                                                                                           aSource,
                                                                                           Locale.US);

    final List <String []> aIssues = new ArrayList <> ();
    for (final ValidationResult aResult : aResultList)
    {
      final EValidationBaseType eBaseType = aResult.getValidationArtefact ()
                                                    .getValidationType ()
                                                    .getBaseType ();
      final String sIssueType = eBaseType.isXSD () ? "SV" : "BV";
      for (final IError aError : aResult.getErrorList ())
        if (aError.getErrorLevel ().isGE (EErrorLevel.ERROR))
          aIssues.add (new String [] { sIssueType, aError.getErrorText (Locale.US) });
    }
    LOGGER.info ("Phive found " + aIssues.size () + " issue(s) for VESID " + aVESID.getAsSingleID ());
    return aIssues;
  }

  private static void _sendWebhook (final byte @NonNull [] aXmlBytes,
                                    @NonNull final String sSenderURI,
                                    @NonNull final String sReceiverURI,
                                    @NonNull final String sDocTypeURI,
                                    @NonNull final String sProcessURI,
                                    @NonNull final String sFilePath)
  {
    final String sWebhookUrl = APConfig.getWebhookUrl ();
    if (StringHelper.hasNoText (sWebhookUrl))
      return;

    final String sXml = new String (aXmlBytes, StandardCharsets.UTF_8);
    final String sToken = APConfig.getWebhookToken ();

    try
    {
      final HttpRequest.Builder aBuilder = HttpRequest.newBuilder ()
                                                      .uri (URI.create (sWebhookUrl))
                                                      .timeout (Duration.ofSeconds (30))
                                                      .header ("Content-Type", "application/xml; charset=utf-8")
                                                      .header ("X-Peppol-Sender", sSenderURI)
                                                      // The receiver from the Peppol envelope: the app uses it
                                                      // to find the participant the document was sent to
                                                      .header ("X-Peppol-Receiver", sReceiverURI)
                                                      .header ("X-Peppol-DocType", sDocTypeURI)
                                                      .header ("X-Peppol-Process", sProcessURI)
                                                      .header ("X-Peppol-Filepath", sFilePath)
                                                      .POST (HttpRequest.BodyPublishers.ofString (sXml, StandardCharsets.UTF_8));
      if (StringHelper.hasText (sToken))
        aBuilder.header ("X-Webhook-Token", sToken);

      final HttpClient aClient = HttpClient.newBuilder ().connectTimeout (Duration.ofSeconds (10)).build ();
      final HttpResponse <String> aResponse = aClient.send (aBuilder.build (), HttpResponse.BodyHandlers.ofString ());
      LOGGER.info ("Webhook delivered to " + sWebhookUrl + " → HTTP " + aResponse.statusCode ());
    }
    catch (final Exception ex)
    {
      LOGGER.error ("Webhook delivery failed to " + sWebhookUrl, ex);
    }
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

    IParticipantIdentifier aMLSReceiver;
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
      LOGGER.info (" MLS Receiver = " + aMLSReceiver.getURIEncoded ());

    // Write SBD to disk only when dump is enabled (disabled by default in production)
    File aFile = null;
    if (APConfig.isIncomingDumpEnabled ())
    {
      aFile = StorageHelper.getStorageFile (aMessageMetadata, ".sbd");
      LOGGER.info ("Now writing SBD to '" + aFile.getAbsolutePath () + "' (" + aSBDBytes.length + " bytes)");

      if (SimpleFileIO.writeFile (aFile, aSBDBytes).isFailure ())
        throw new IllegalStateException ("Failed to write SBD to '" +
                                         aFile.getAbsolutePath () +
                                         "' (" +
                                         aSBDBytes.length +
                                         " bytes)");
      LOGGER.info ("Successfully wrote SBD to '" + aFile.getAbsolutePath () + "'");
    }

    // Send MLS (Message Level Status) response back to the sender's AP
    final String sIncomingProcess = aPeppolSBD.getProcessURIEncoded ();
    final boolean bIsIncomingMLS = sIncomingProcess != null && sIncomingProcess.contains ("edec:mls");
    if (bIsIncomingMLS)
    {
      // An MLS is the answer to a document. It is never answered with another MLS: the sender of
      // an MLS does not expect one (its MLS address is often not even registered for receiving
      // MLS), and two Access Points answering each other's MLS would never stop.
      LOGGER.info ("Received an MLS from " + aPeppolSBD.getSenderURIEncoded () + " - no MLS response is sent");
      aMLSReceiver = null;
    }

    // Extract UBL element once (reused for MLS validation and TDD generation)
    Element aUBLElement = null;
    {
      final Document aSBDDoc = DOMReader.readXMLDOM (aSBDBytes);
      if (aSBDDoc != null)
        for (Node aChild = aSBDDoc.getDocumentElement ().getFirstChild (); aChild != null; aChild = aChild.getNextSibling ())
          if (aChild.getNodeType () == Node.ELEMENT_NODE &&
              !"StandardBusinessDocumentHeader".equals (aChild.getLocalName ()))
          {
            aUBLElement = (Element) aChild;
            break;
          }
    }

    // Serialize the business document ONCE, here on the request thread, before
    // any background task is started. A DOM tree must not be read by several
    // threads at the same time (Xerces fails with a NullPointerException in
    // ParentNode.nodeListItem), so every consumer below gets these bytes and
    // parses a DOM of its own.
    byte [] aUBLBytesTmp = null;
    if (aUBLElement != null)
      try
      {
        aUBLBytesTmp = XMLWriter.getNodeAsBytes (aUBLElement);
      }
      catch (final RuntimeException ex)
      {
        LOGGER.error ("Failed to serialize the incoming business document", ex);
      }
    final byte [] aUBLBytes = aUBLBytesTmp;
    if (aUBLElement != null && aUBLBytes == null)
      LOGGER.error ("The incoming business document could not be serialized - webhook and AE TDD are skipped");

    if (bIsIncomingMLS && aUBLBytes != null)
    {
      // Tell the ASP application the verdict (of the tax authority on a TDD, or
      // of the receiving Access Point on an invoice)
      final String sMLSSenderURI = aPeppolSBD.getSenderURIEncoded ();
      PhotonWorkerPool.getInstance ()
                      .run (CAS4.LIB_NAME + " Forward received MLS",
                            () -> MLSResultNotifier.notifyReceivedMLS (aUBLBytes, sMLSSenderURI));
    }

    final String sInstanceID = aSBD.getStandardBusinessDocumentHeader ()
                                   .getDocumentIdentification ()
                                   .getInstanceIdentifier ();

    // Fire webhook for invoice/creditnote documents (not MLS) and afterwards
    // send the AE TDD for PINT-AE documents. Both run in ONE task, in this
    // order, so the ASP application has stored the inbound invoice before the
    // TDD result for it is reported.
    final String sDocTypeURI = aPeppolSBD.getDocumentTypeURIEncoded ();
    final boolean bIsPintAE = sDocTypeURI != null && sDocTypeURI.contains ("@ae-1");

    // Validate first: the result decides the MLS response (AP or RE) AND the type
    // of the Tax Data Document (SUBMIT for a valid document, FAILED otherwise)
    List <String []> aValidationErrorsTmp;
    try
    {
      aValidationErrorsTmp = bIsIncomingMLS || sDocTypeURI == null ? Collections.emptyList ()
                                                                   : _getValidationErrors (aUBLBytes, sDocTypeURI);
    }
    catch (final Throwable t)
    {
      LOGGER.error ("Validation threw unexpected error", t);
      aValidationErrorsTmp = bIsPintAE ? _notValidated ("internal validation error") : Collections.emptyList ();
    }
    final List <String []> aValidationErrors = aValidationErrorsTmp;
    final boolean bValidationFailed = !aValidationErrors.isEmpty ();
    LOGGER.info ("Validation result: " + aValidationErrors.size () + " error(s) found");
    if (!bIsIncomingMLS && aUBLBytes != null)
    {
      final String sFilePath = aFile != null ? aFile.getAbsolutePath () : "";
      final String sTransportHeaderID = sInstanceID != null ? sInstanceID : UUID.randomUUID ().toString ();
      // The MLS of the tax authority refers to the TDD by this ID
      final String sTDDInstanceID = UUID.randomUUID ().toString ();
      PhotonWorkerPool.getInstance ()
                      .run (CAS4.LIB_NAME + " Incoming invoice webhook + AE TDD",
                            () -> {
                              try
                              {
                                _sendWebhook (aUBLBytes,
                                              aPeppolSBD.getSenderURIEncoded (),
                                              aPeppolSBD.getReceiverURIEncoded (),
                                              sDocTypeURI,
                                              aPeppolSBD.getProcessURIEncoded (),
                                              sFilePath);
                              }
                              catch (final Throwable ex)
                              {
                                LOGGER.error ("Webhook async error", ex);
                              }

                              if (!bIsPintAE)
                                return;

                              LOGGER.info ("TDD async task starting for transport header: " + sTransportHeaderID);
                              // Tell the ASP application the real outcome - "Reported to FTA"
                              // is only set when the TDD got a valid AS4 receipt
                              final String sReceiverURI = aPeppolSBD.getReceiverURIEncoded ();
                              // A DOM used by this task only
                              final Document aTaskDoc = DOMReader.readXMLDOM (aUBLBytes);
                              if (aTaskDoc == null || aTaskDoc.getDocumentElement () == null)
                              {
                                LOGGER.error ("Failed to parse the business document for the AE TDD");
                                return;
                              }
                              final Element aFinalUBLElement = aTaskDoc.getDocumentElement ();
                              try
                              {
                                final EUAETDDDocumentTypeCode eTDDType = _sendTDD (aFinalUBLElement,
                                                                                   sTransportHeaderID,
                                                                                   bValidationFailed,
                                                                                   sTDDInstanceID,
                                                                                   false);
                                TDDResultNotifier.notifyResult (TDDResultNotifier.DIRECTION_INBOUND,
                                                                sReceiverURI,
                                                                aFinalUBLElement,
                                                                sTransportHeaderID,
                                                                true,
                                                                eTDDType.name () +
                                                                      " TDD sent to " +
                                                                      APConfig.getAETDDReceiver (),
                                                                sTDDInstanceID,
                                                                eTDDType == EUAETDDDocumentTypeCode.FAILED ? "F" : "S");
                              }
                              catch (final Throwable ex)
                              {
                                LOGGER.error ("Failed to send AE TDD", ex);
                                TDDResultNotifier.notifyResult (TDDResultNotifier.DIRECTION_INBOUND,
                                                                sReceiverURI,
                                                                aFinalUBLElement,
                                                                sTransportHeaderID,
                                                                false,
                                                                ex.getMessage () != null ? ex.getMessage ()
                                                                                         : ex.getClass ().getName (),
                                                                sTDDInstanceID,
                                                                bValidationFailed ? "F" : "S");
                              }
                            });
    }

    if (aMLSReceiver != null)
    {
      final IParticipantIdentifier aFinalMLSReceiver = aMLSReceiver;
      final List <String []> aFinalValidationErrors = aValidationErrors;
      PhotonWorkerPool.getInstance ()
                      .run (CAS4.LIB_NAME + " Send Peppol MLS response",
                            () -> {
                              LOGGER.info ("MLS async task starting for receiver: " + aFinalMLSReceiver.getURIEncoded ());
                              try
                              {
                                _sendMLS (aPeppolSBD, aFinalMLSReceiver, sInstanceID, aFinalValidationErrors);
                              }
                              catch (final Throwable ex)
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

  /**
   * Extract BTAE-20 value directly from the UBL DOM element.
   * Works even when UBL21Marshaller fails to parse (e.g. syntax-invalid invoices).
   * Looks for AdditionalDocumentReference/DocumentTypeCode='aedtotal-incl-vat' and returns
   * the DocumentDescription value stripped of the "AED " prefix.
   */
  @Nullable
  private static String _getBTAE20FromDOM (@NonNull final Element aUBLElement)
  {
    final String CAC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    final String CBC_NS = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    for (Node aChild = aUBLElement.getFirstChild (); aChild != null; aChild = aChild.getNextSibling ())
    {
      if (aChild.getNodeType () == Node.ELEMENT_NODE &&
          CAC_NS.equals (aChild.getNamespaceURI ()) &&
          "AdditionalDocumentReference".equals (aChild.getLocalName ()))
      {
        String sDocTypeCode = null;
        String sDocDesc = null;
        for (Node aGC = aChild.getFirstChild (); aGC != null; aGC = aGC.getNextSibling ())
          if (aGC.getNodeType () == Node.ELEMENT_NODE && CBC_NS.equals (aGC.getNamespaceURI ()))
          {
            if ("DocumentTypeCode".equals (aGC.getLocalName ()))
              sDocTypeCode = aGC.getTextContent ();
            else
              if ("DocumentDescription".equals (aGC.getLocalName ()))
                sDocDesc = aGC.getTextContent ();
          }
        if ("aedtotal-incl-vat".equals (sDocTypeCode) && sDocDesc != null)
        {
          final String sTrimmed = sDocDesc.trim ();
          return sTrimmed.startsWith ("AED ") ? sTrimmed.substring (4).trim () : sTrimmed;
        }
      }
    }
    return null;
  }

  private static void _sendMLS (@NonNull final PeppolSBDHData aPeppolSBD,
                                 @NonNull final IParticipantIdentifier aMLSReceiver,
                                 @Nullable final String sOriginalInstanceID,
                                 @NonNull final List <String []> aValidationErrors) throws Exception
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

    // Build LineResponse elements for each issue (SV or BV)
    final StringBuilder sbLineResponses = new StringBuilder ();
    for (final String [] aIssue : aValidationErrors)
      sbLineResponses.append ("<cac:LineResponse>")
                     .append ("<cac:LineReference><cbc:LineID>/</cbc:LineID></cac:LineReference>")
                     .append ("<cac:Response>")
                     .append ("<cbc:ResponseCode>RE</cbc:ResponseCode>")
                     .append ("<cbc:Description>")
                     .append (_escapeXML (aIssue[1]))
                     .append ("</cbc:Description>")
                     .append ("<cac:Status>")
                     .append ("<cbc:StatusReasonCode listID=\"urn:peppol:edec:mls:issue-type\">")
                     .append (aIssue[0])
                     .append ("</cbc:StatusReasonCode>")
                     .append ("</cac:Status>")
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
                                "</cbc:ResponseCode><cbc:Description>" +
                                (aValidationErrors.isEmpty () ? "Accepted"
                                                              : "Rejected: " + aValidationErrors.size () + " business validation error(s)") +
                                "</cbc:Description></cac:Response>" +
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

  /**
   * Build and send the AE TDD.
   *
   * @return The TDD document type that was sent with a valid AS4 receipt.
   * @throws Exception
   *         If the TDD could not be built or was not accepted.
   */
  /**
   * Build the AE Tax Data Document for a received invoice or credit note and
   * send it to the tax authority. Used for re-sending.
   *
   * @param aUBLElement
   *        The UBL Invoice or CreditNote root element
   * @param sTransportHeaderID
   *        The SBDH instance identifier of the invoice
   * @param bValidationFailed
   *        <code>true</code> to report the document as FAILED
   * @param sTDDInstanceID
   *        The SBDH instance identifier to use for the TDD
   * @return The type of the TDD that was sent
   * @throws Exception
   *         if the TDD could not be built or was not accepted with a valid AS4
   *         receipt
   */
  @NonNull
  public static EUAETDDDocumentTypeCode sendInboundTDD (@NonNull final Element aUBLElement,
                                                        @NonNull final String sTransportHeaderID,
                                                        final boolean bValidationFailed,
                                                        @NonNull final String sTDDInstanceID) throws Exception
  {
    return _sendTDD (aUBLElement, sTransportHeaderID, bValidationFailed, sTDDInstanceID, false);
  }

  /**
   * As {@link #sendInboundTDD(Element, String, boolean, String)}, for a
   * corrected report.
   *
   * @param aUBLElement
   *        The UBL Invoice or CreditNote root element
   * @param sTransportHeaderID
   *        The SBDH instance identifier of the invoice
   * @param bValidationFailed
   *        <code>true</code> to report the document as FAILED
   * @param sTDDInstanceID
   *        The SBDH instance identifier to use for the TDD
   * @param bResubmit
   *        <code>true</code> to send the report with the type RESUBMIT, after
   *        the tax authority rejected the previous one
   * @return The type of the TDD that was sent
   * @throws Exception
   *         if the TDD could not be built, is invalid, or was not accepted
   *         with a valid AS4 receipt
   */
  @NonNull
  public static EUAETDDDocumentTypeCode sendInboundTDD (@NonNull final Element aUBLElement,
                                                        @NonNull final String sTransportHeaderID,
                                                        final boolean bValidationFailed,
                                                        @NonNull final String sTDDInstanceID,
                                                        final boolean bResubmit) throws Exception
  {
    return _sendTDD (aUBLElement, sTransportHeaderID, bValidationFailed, sTDDInstanceID, bResubmit);
  }

  @NonNull
  private static EUAETDDDocumentTypeCode _sendTDD (@NonNull final Element aUBLElement,
                                                   @NonNull final String sTransportHeaderID,
                                                   final boolean bValidationFailed,
                                                   @NonNull final String sTDDInstanceID,
                                                   final boolean bResubmit) throws Exception
  {
    // The type of a report that is not FAILED
    final EUAETDDDocumentTypeCode eReportType = bResubmit ? EUAETDDDocumentTypeCode.RESUBMIT
                                                          : EUAETDDDocumentTypeCode.SUBMIT;
    final String sSeatID = APConfig.getMyPeppolSeatID ();

    final String sLocalName = aUBLElement.getLocalName ();
    final boolean bIsInvoice = "Invoice".equals (sLocalName);
    final boolean bIsCreditNote = "CreditNote".equals (sLocalName);
    if (!bIsInvoice && !bIsCreditNote)
    {
      throw new IllegalStateException ("Cannot send TDD for unknown UBL element: " + sLocalName);
    }

    // Serialize UBL element to bytes for UBL21 parsing
    final byte [] aUBLBytes = XMLWriter.getNodeAsBytes (aUBLElement);
    if (aUBLBytes == null)
    {
      throw new IllegalStateException ("Failed to serialize UBL element for TDD");
    }

    // C5 = Tax Authority (testbed or production FTA)
    // e.g. "0242:000006-testbed.3001" — use SimpleParticipantIdentifier to bypass ICD validation
    final String sTDDReceiverStr = APConfig.getAETDDReceiver ();
    final IParticipantIdentifier aTDDReceiver = new SimpleParticipantIdentifier ("iso6523-actorid-upis", sTDDReceiverStr);

    // C4 = us (the receiving AP), scheme 0235
    final IParticipantIdentifier aSenderID = PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme ("0235:" +
                                                                                                                            sSeatID);

    // Build TDD
    final PeppolUAETDD10Builder aTDDBuilder = new PeppolUAETDD10Builder ().documentTypeCode (eReportType)
                                                                          .reporterRole (EUAETDDReporterRole.RECEIVER)
                                                                          // C4 = us (receiving AP)
                                                                          .reportingParty (aSenderID)
                                                                          // C5 = Tax Authority
                                                                          .receivingParty (new SimpleParticipantIdentifier ("iso6523-actorid-upis", sTDDReceiverStr))
                                                                          // C2/C3 = our AP, scheme 0242, strip "POP" prefix
                                                                          .reportersRepresentative (PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme ("0242:" +
                                                                                                                                                                                  sSeatID.substring (3)));

    // Use arrays so aInvoice/aCreditNote are accessible in the FAILED fallback lambda
    final InvoiceType[] aInvoiceRef = { null };
    final CreditNoteType[] aCreditNoteRef = { null };

    if (bIsInvoice)
    {
      aInvoiceRef[0] = UBL21Marshaller.invoice ().read (aUBLBytes);
      if (aInvoiceRef[0] == null)
      {
        // Parse failed (e.g. syntax-invalid invoice) — skip SUBMIT setup, FAILED TDS fallback will run
        LOGGER.warn ("Failed to parse Invoice for TDD - will attempt FAILED TDS directly");
      }
      else
      {
      aTDDBuilder.documentScope (EUAETDDDocumentScope.DOMESTIC)
                 .reportedTransaction (rt -> {
                   rt.transportHeaderID (sTransportHeaderID).initFromInvoice (aInvoiceRef[0]);
                   // BTAE-20: only present when DocCurrency != AED
                   // Stored in AdditionalDocumentReference[DocumentTypeCode='aedtotal-incl-vat']/DocumentDescription as "AED <value>"
                   final var aAEDocRef = CollectionFind.findFirst (aInvoiceRef[0].getAdditionalDocumentReference (),
                                                                   r -> "aedtotal-incl-vat".equals (r.getDocumentTypeCodeValue ()) &&
                                                                        !r.getDocumentDescription ().isEmpty ());
                   if (aAEDocRef != null)
                   {
                     final String sRaw = aAEDocRef.getDocumentDescription ().get (0).getValue ();
                     if (sRaw != null)
                     {
                       final String sTrimmed = sRaw.trim ();
                       final String sBTAE20 = sTrimmed.startsWith ("AED ") ? sTrimmed.substring (4).trim () : sTrimmed;
                       rt.addCustomContent (new PeppolUAETDD10ReportedTransactionBuilder.CustomContent ("BTAE-20", sBTAE20));
                     }
                   }
                 });
      } // end else (aInvoiceRef[0] != null)
    }
    else
    {
      aCreditNoteRef[0] = UBL21Marshaller.creditNote ().read (aUBLBytes);
      if (aCreditNoteRef[0] == null)
      {
        // Parse failed (e.g. syntax-invalid credit note) — skip SUBMIT setup, FAILED TDS fallback will run
        LOGGER.warn ("Failed to parse CreditNote for TDD - will attempt FAILED TDS directly");
      }
      else
      {
      aTDDBuilder.documentScope (EUAETDDDocumentScope.DOMESTIC)
                 .reportedTransaction (rt -> {
                   rt.transportHeaderID (sTransportHeaderID).initFromCreditNote (aCreditNoteRef[0]);
                   // BTAE-20: only present when DocCurrency != AED
                   // Stored in AdditionalDocumentReference[DocumentTypeCode='aedtotal-incl-vat']/DocumentDescription as "AED <value>"
                   final var aAEDocRef = CollectionFind.findFirst (aCreditNoteRef[0].getAdditionalDocumentReference (),
                                                                   r -> "aedtotal-incl-vat".equals (r.getDocumentTypeCodeValue ()) &&
                                                                        !r.getDocumentDescription ().isEmpty ());
                   if (aAEDocRef != null)
                   {
                     final String sRaw = aAEDocRef.getDocumentDescription ().get (0).getValue ();
                     if (sRaw != null)
                     {
                       final String sTrimmed = sRaw.trim ();
                       final String sBTAE20 = sTrimmed.startsWith ("AED ") ? sTrimmed.substring (4).trim () : sTrimmed;
                       rt.addCustomContent (new PeppolUAETDD10ReportedTransactionBuilder.CustomContent ("BTAE-20", sBTAE20));
                     }
                   }
                 });
      } // end else (aCreditNoteRef[0] != null)
    }

    EUAETDDDocumentTypeCode eTDDType = eReportType;
    // A document that failed validation is reported as FAILED, never as SUBMIT
    TaxDataType aTDD = bValidationFailed ? null : aTDDBuilder.build ();
    if (aTDD != null)
    {
      // Seller and buyer name (ibr-tdd-58, ibr-tdd-60)
      TDDPartyNames.applyFromInvoice (aTDD, aInvoiceRef[0]);
      TDDPartyNames.applyFromCreditNote (aTDD, aCreditNoteRef[0]);
    }
    else
    {
      eTDDType = EUAETDDDocumentTypeCode.FAILED;
      // The document failed validation, or the SUBMIT build failed (e.g. required fields missing).
      // FAILED TDS — only requires TransportHeaderID + SourceDocument + BTAE-20
      LOGGER.warn (bValidationFailed ? "The document failed validation - reporting it as FAILED TDS"
                                     : "SUBMIT TDD build failed - retrying as FAILED TDS (invalid document)");
      aTDD = new PeppolUAETDD10Builder ().documentTypeCode (EUAETDDDocumentTypeCode.FAILED)
                                        .documentScope (EUAETDDDocumentScope.DOMESTIC)
                                        .reporterRole (EUAETDDReporterRole.RECEIVER)
                                        .reportingParty (aSenderID)
                                        .receivingParty (new SimpleParticipantIdentifier ("iso6523-actorid-upis",
                                                                                          sTDDReceiverStr))
                                        .reportersRepresentative (PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme ("0242:" +
                                                                                                                                                sSeatID.substring (3)))
                                        .reportedTransaction (rt -> {
                                          rt.transportHeaderID (sTransportHeaderID).sourceDocument (aUBLElement);
                                          // BTAE-20: read from DOM directly — works even when UBL parse failed
                                          final String sBTAE20 = _getBTAE20FromDOM (aUBLElement);
                                          if (sBTAE20 != null)
                                            rt.addCustomContent (new PeppolUAETDD10ReportedTransactionBuilder.CustomContent ("BTAE-20", sBTAE20));
                                        })
                                        .build ();
    }
    if (aTDD == null)
    {
      throw new IllegalStateException ("Failed to build AE TDD - builder returned null");
    }

    final byte [] aTDDBytes = new PeppolUAETDD10Marshaller ().getAsBytes (aTDD);
    if (aTDDBytes == null)
    {
      throw new IllegalStateException ("Failed to serialize AE TDD to bytes");
    }
    // The TDD is validated before it is sent (UAE solution architecture)
    TDDValidation.assertValid (aTDDBytes);
    LOGGER.info ("Sending AE TDD of type " +
                 eTDDType.getID () +
                 " (" +
                 aTDDBytes.length +
                 " bytes) to " +
                 aTDDReceiver.getURIEncoded ());

    // Send via AS4
    final IDocumentTypeIdentifier aTDDDocTypeID = PeppolIdentifierFactory.INSTANCE.createDocumentTypeIdentifierWithDefaultScheme ("urn:peppol:schema:taxdata:1.0::TaxData##urn:peppol:taxdata:ae-1::1.0");
    final IProcessIdentifier aTDDProcessID = PeppolIdentifierFactory.INSTANCE.createProcessIdentifierWithDefaultScheme ("urn:peppol:taxreporting");

    final EPeppolNetwork eStage = APConfig.getPeppolStage ();
    final TrustedCAChecker aCAChecker = eStage.isTest () ? PeppolTrustedCA.peppolTestAP ()
                                                         : PeppolTrustedCA.peppolProductionAP ();

    final SMPClientReadOnly aSMPClient = new SMPClientReadOnly (Phase4PeppolSender.URL_PROVIDER,
                                                                aTDDReceiver,
                                                                eStage.getSMLInfo ());
    APConfig.applyHttpProxySettings (aSMPClient.httpClientSettings ());

    final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
    APConfig.applyHttpProxySettings (aHCS);

    final Document aTDDDoc = DOMReader.readXMLDOM (aTDDBytes);
    if (aTDDDoc == null)
    {
      throw new IllegalStateException ("Failed to re-parse TDD XML for sending");
    }

    final Wrapper <Phase4Exception> aTDDEx = new Wrapper <> ();
    final Wrapper <String> aTDDSignalErrors = new Wrapper <> ();
    final EAS4UserMessageSendResult eResult = Phase4PeppolSender.builder ()
                                                                .sbdhInstanceIdentifier (sTDDInstanceID)
                                                                .httpClientFactory (aHCS)
                                                                .documentTypeID (aTDDDocTypeID)
                                                                .processID (aTDDProcessID)
                                                                .senderParticipantID (aSenderID)
                                                                .receiverParticipantID (aTDDReceiver)
                                                                .senderPartyID (sSeatID)
                                                                .countryC1 (APConfig.getMyPeppolCountryCode ())
                                                                .payload (aTDDDoc.getDocumentElement ())
                                                                // MLS metadata: ask Tax Authority to send MLS back to us
                                                                .mlsType (EPeppolMLSType.ALWAYS_SEND)
                                                                .mlsTo (new SimpleParticipantIdentifier ("iso6523-actorid-upis",
                                                                                                        "0242:" +
                                                                                                                               sSeatID.substring (3)))
                                                                .peppolAP_CAChecker (aCAChecker)
                                                                .smpClient (aSMPClient)
                                                                .signalMsgConsumer ( (aSignalMsg,
                                                                                      aMessageMetadata,
                                                                                      aState) -> aTDDSignalErrors.set (PeppolSender.getSignalMessageErrors (aSignalMsg)))
                                                                .sendMessageAndCheckForReceipt (aTDDEx::set);
    LOGGER.info ("AE TDD send result to " + aTDDReceiver.getURIEncoded () + ": " + eResult);
    if (!eResult.isSuccess ())
      throw new IllegalStateException ("AE TDD was not accepted by " +
                                       aTDDReceiver.getURIEncoded () +
                                       ": " +
                                       eResult +
                                       (StringHelper.hasText (aTDDSignalErrors.get ()) ? " - " + aTDDSignalErrors.get () : "") +
                                       (aTDDEx.isSet () ? " - " + aTDDEx.get ().getMessage () : ""),
                                       aTDDEx.get ());
    return eTDDType;
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
