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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.helger.annotation.Nonempty;
import com.helger.annotation.concurrent.Immutable;
import com.helger.base.timing.StopWatch;
import com.helger.base.wrapper.Wrapper;
import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.diver.api.coord.DVRCoordinate;
import com.helger.collection.CollectionFind;
import com.helger.peppol.sbdh.EPeppolMLSType;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.security.PeppolTrustedCA;
import com.helger.peppol.servicedomain.EPeppolNetwork;
import com.helger.peppol.sml.ISMLInfo;
import com.helger.peppol.uae.tdd.PeppolUAETDD10Builder;
import com.helger.peppol.uae.tdd.PeppolUAETDD10ReportedTransactionBuilder;
import com.helger.peppol.uae.tdd.codelist.EUAETDDDocumentScope;
import com.helger.peppol.uae.tdd.codelist.EUAETDDDocumentTypeCode;
import com.helger.peppol.uae.tdd.codelist.EUAETDDReporterRole;
import com.helger.peppol.uae.tdd.jaxb.PeppolUAETDD10Marshaller;
import com.helger.peppol.uae.tdd.v100.TaxDataType;
import com.helger.photon.io.PhotonWorkerPool;
import com.helger.ubl21.UBL21Marshaller;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.IIdentifierFactory;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.peppolid.simple.participant.SimpleParticipantIdentifier;
import com.helger.phase4.client.IAS4ClientBuildMessageCallback;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.model.message.AS4UserMessage;
import com.helger.phase4.model.message.AbstractAS4Message;
import com.helger.phase4.peppol.Phase4PeppolSender;
import com.helger.phase4.peppol.Phase4PeppolSender.PeppolUserMessageBuilder;
import com.helger.phase4.peppol.Phase4PeppolSender.PeppolUserMessageSBDHBuilder;
import com.helger.phase4.peppol.Phase4PeppolSendingReport;
import com.helger.phase4.peppol.server.APConfig;
import com.helger.phase4.peppol.server.reporting.TDDResultNotifier;
import com.helger.phase4.profile.peppol.Phase4PeppolHttpClientSettings;
import com.helger.phase4.sender.EAS4UserMessageSendResult;
import com.helger.phase4.util.Phase4Exception;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.phive.peppol.PeppolValidationPintAE;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;
import com.helger.security.certificate.TrustedCAChecker;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.xml.serialize.read.DOMReader;
import com.helger.xml.serialize.write.XMLWriter;

/**
 * This contains the main Peppol sending code. It was extracted from the controller to make it more
 * readable
 *
 * @author Philip Helger
 */
@Immutable
public final class PeppolSender
{
  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (PeppolSender.class);

  private static final ValidationExecutorSetRegistry <IValidationSourceXML> VES_REGISTRY;
  static
  {
    VES_REGISTRY = new ValidationExecutorSetRegistry <> ();
    PeppolValidationPintAE.init (VES_REGISTRY);
  }

  private PeppolSender ()
  {}

  /**
   * Pre-send validation for PINT-AE documents. Returns list of error messages, empty if valid.
   */
  // Package-private so APIPostSendSBDH can also call it
  @NonNull
  static List <String> _validatePintAE (@NonNull final Element aDocElement, @NonNull final String sDocTypeID)
  {
    if (!sDocTypeID.contains ("@ae-1"))
      return new ArrayList <> ();

    final boolean bIsSelfBilling = sDocTypeID.contains ("selfbilling");
    final String sLocalName = aDocElement.getLocalName ();
    final DVRCoordinate aVESID;
    if ("Invoice".equals (sLocalName))
      aVESID = bIsSelfBilling ? PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_SB_INVOICE_2026_05
                              : PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_INVOICE_2026_05;
    else
      if ("CreditNote".equals (sLocalName))
        aVESID = bIsSelfBilling ? PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_SB_CREDIT_NOTE_2026_05
                                : PeppolValidationPintAE.VID_OPENPEPPOL_AE_PINT_CREDIT_NOTE_2026_05;
      else
        return new ArrayList <> ();

    final IValidationExecutorSet <IValidationSourceXML> aVES = VES_REGISTRY.getOfID (aVESID);
    if (aVES == null)
    {
      LOGGER.warn ("VES not found for VESID: " + aVESID.getAsSingleID ());
      return new ArrayList <> ();
    }

    final byte [] aBytes = XMLWriter.getNodeAsBytes (aDocElement);
    if (aBytes == null)
      return new ArrayList <> ();
    final Document aReDoc = DOMReader.readXMLDOM (aBytes);
    if (aReDoc == null)
      return new ArrayList <> ();

    final IValidationSourceXML aSource = ValidationSourceXML.create (null, aReDoc);
    final ValidationResultList aResultList = ValidationExecutionManager.executeValidation (IValidityDeterminator.createDefault (),
                                                                                           aVES,
                                                                                           aSource,
                                                                                           Locale.US);
    final List <String> aErrors = new ArrayList <> ();
    for (final ValidationResult aResult : aResultList)
      for (final IError aError : aResult.getErrorList ())
        if (aError.getErrorLevel ().isGE (EErrorLevel.ERROR))
          aErrors.add (aError.getErrorText (Locale.US));

    LOGGER.info ("Pre-send PINT-AE validation: " + aErrors.size () + " error(s) for " + sLocalName);
    return aErrors;
  }

  /**
   * Send a Peppol message where the SBDH is created internally by phase4
   *
   * @param aSmlInfo
   *        The SML to be used for receiver lookup
   * @param aAPCAChecker
   *        The Peppol CA checker to be used.
   * @param aPayloadBytes
   *        The main business document to be send
   * @param sSenderID
   *        The Peppol sender Participant ID
   * @param sReceiverID
   *        The Peppol receiver Participant ID
   * @param sDocTypeID
   *        The Peppol document type ID
   * @param sProcessID
   *        The Peppol process ID
   * @param sCountryCodeC1
   *        The Country Code of the sender (C1)
   * @return The created sending report and never <code>null</code>.
   */
  @NonNull
  public static Phase4PeppolSendingReport sendPeppolMessageCreatingSbdh (@NonNull final ISMLInfo aSmlInfo,
                                                                         @NonNull final TrustedCAChecker aAPCAChecker,
                                                                         final byte @NonNull [] aPayloadBytes,
                                                                         @NonNull @Nonempty final String sSenderID,
                                                                         @NonNull @Nonempty final String sReceiverID,
                                                                         @NonNull @Nonempty final String sDocTypeID,
                                                                         @NonNull @Nonempty final String sProcessID,
                                                                         @NonNull @Nonempty final String sCountryCodeC1)
  {
    final IIdentifierFactory aIF = PeppolIdentifierFactory.INSTANCE;
    final String sMyPeppolSeatID = APConfig.getMyPeppolSeatID ();

    final Phase4PeppolSendingReport aSendingReport = new Phase4PeppolSendingReport (aSmlInfo);
    aSendingReport.setCountryC1 (sCountryCodeC1);
    aSendingReport.setSenderPartyID (sMyPeppolSeatID);

    // Capture SBDH instance ID for TDD transport header
    final Wrapper <String> aSBDHIDHolder = new Wrapper <> ();

    EAS4UserMessageSendResult eResult = null;
    boolean bExceptionCaught = false;
    final StopWatch aSW = StopWatch.createdStarted ();
    try
    {
      // Payload must be XML - even for Text and Binary content
      final Document aDoc = DOMReader.readXMLDOM (aPayloadBytes);
      if (aDoc == null || aDoc.getDocumentElement () == null)
        throw new IllegalStateException ("Failed to read provided payload as XML");
      if (aDoc.getDocumentElement ().getNamespaceURI () == null)
        throw new IllegalStateException ("Only XML payloads with a namespace are supported");

      // Pre-send PINT-AE validation — reject invalid documents before sending
      final List <String> aValidationErrors = _validatePintAE (aDoc.getDocumentElement (), sDocTypeID);
      if (!aValidationErrors.isEmpty ())
        throw new IllegalStateException ("Pre-send PINT-AE validation failed with " +
                                         aValidationErrors.size () +
                                         " error(s): " +
                                         String.join ("; ", aValidationErrors));

      // Start configuring here
      IParticipantIdentifier aSenderID = aIF.parseParticipantIdentifier (sSenderID);
      if (aSenderID == null)
      {
        // Fallback to default scheme
        aSenderID = aIF.createParticipantIdentifierWithDefaultScheme (sSenderID);
      }
      if (aSenderID == null)
        throw new IllegalStateException ("Failed to parse the sending participant ID '" + sSenderID + "'");
      aSendingReport.setSenderID (aSenderID);

      IParticipantIdentifier aReceiverID = aIF.parseParticipantIdentifier (sReceiverID);
      if (aReceiverID == null)
      {
        // Fallback to default scheme
        aReceiverID = aIF.createParticipantIdentifierWithDefaultScheme (sReceiverID);
      }
      if (aReceiverID == null)
        throw new IllegalStateException ("Failed to parse the receiving participant ID '" + sReceiverID + "'");
      aSendingReport.setReceiverID (aReceiverID);

      IDocumentTypeIdentifier aDocTypeID = aIF.parseDocumentTypeIdentifier (sDocTypeID);
      if (aDocTypeID == null)
      {
        // Fallback to default scheme
        aDocTypeID = aIF.createDocumentTypeIdentifierWithDefaultScheme (sDocTypeID);
      }
      if (aDocTypeID == null)
        throw new IllegalStateException ("Failed to parse the document type ID '" + sDocTypeID + "'");
      aSendingReport.setDocTypeID (aDocTypeID);

      IProcessIdentifier aProcessID = aIF.parseProcessIdentifier (sProcessID);
      if (aProcessID == null)
      {
        // Fallback to default scheme
        aProcessID = aIF.createProcessIdentifierWithDefaultScheme (sProcessID);
      }
      if (aProcessID == null)
        throw new IllegalStateException ("Failed to parse the process ID '" + sProcessID + "'");
      aSendingReport.setProcessID (aProcessID);

      final SMPClientReadOnly aSMPClient = new SMPClientReadOnly (Phase4PeppolSender.URL_PROVIDER,
                                                                  aReceiverID,
                                                                  aSmlInfo);
      APConfig.applyHttpProxySettings (aSMPClient.httpClientSettings ());

      final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
      APConfig.applyHttpProxySettings (aHCS);

      final PeppolUserMessageBuilder aBuilder = Phase4PeppolSender.builder ()
                                                                  .httpClientFactory (aHCS)
                                                                  .documentTypeID (aDocTypeID)
                                                                  .processID (aProcessID)
                                                                  .senderParticipantID (aSenderID)
                                                                  .receiverParticipantID (aReceiverID)
                                                                  .senderPartyID (sMyPeppolSeatID)
                                                                  .countryC1 (sCountryCodeC1)
                                                                  .mlsTo (new SimpleParticipantIdentifier ("iso6523-actorid-upis", "0242:" + sMyPeppolSeatID.substring (3)))
                                                                  .mlsType (EPeppolMLSType.ALWAYS_SEND)
                                                                  .payload (aDoc.getDocumentElement ())
                                                                  .peppolAP_CAChecker (aAPCAChecker)
                                                                  .smpClient (aSMPClient)
                                                                  .sbdDocumentConsumer (aSBD -> {
                                                                    // Remember SBDH Instance Identifier
                                                                    final String sSBDHID = aSBD.getStandardBusinessDocumentHeader ()
                                                                                               .getDocumentIdentification ()
                                                                                               .getInstanceIdentifier ();
                                                                    aSendingReport.setSBDHInstanceIdentifier (sSBDHID);
                                                                    aSBDHIDHolder.set (sSBDHID);
                                                                  })
                                                                  .endpointURLConsumer (aSendingReport::setC3EndpointURL)
                                                                  .technicalContactConsumer (aSendingReport::setC3TechnicalContact)
                                                                  .certificateConsumer ( (aAPCertificate,
                                                                                          aCheckDT,
                                                                                          eCertCheckResult) -> {
                                                                    // Determined by SMP lookup
                                                                    aSendingReport.setC3Cert (aAPCertificate);
                                                                    aSendingReport.setC3CertCheckDT (aCheckDT);
                                                                    aSendingReport.setC3CertCheckResult (eCertCheckResult);
                                                                  })
                                                                  .sendingDateTimeConsumer (aSendingReport::setAS4SendingDT)
                                                                  .buildMessageCallback (new IAS4ClientBuildMessageCallback ()
                                                                  {
                                                                    public void onAS4Message (@NonNull final AbstractAS4Message <?> aMsg)
                                                                    {
                                                                      // Created AS4 fields
                                                                      final AS4UserMessage aUserMsg = (AS4UserMessage) aMsg;
                                                                      aSendingReport.setAS4MessageID (aUserMsg.getEbms3UserMessage ()
                                                                                                              .getMessageInfo ()
                                                                                                              .getMessageId ());
                                                                      aSendingReport.setAS4ConversationID (aUserMsg.getEbms3UserMessage ()
                                                                                                                   .getCollaborationInfo ()
                                                                                                                   .getConversationId ());
                                                                    }
                                                                  })
                                                                  .rawResponseConsumer (aSendingReport::setRawHttpResponse)
                                                                  .signalMsgConsumer ( (aSignalMsg,
                                                                                        aMessageMetadata,
                                                                                        aState) -> {
                                                                    aSendingReport.setAS4ReceivedSignalMsg (aSignalMsg);
                                                                  });
      final Wrapper <Phase4Exception> aCaughtEx = new Wrapper <> ();
      eResult = aBuilder.sendMessageAndCheckForReceipt (aCaughtEx::set);
      LOGGER.info ("Peppol client send result: " + eResult);

      if (eResult.isSuccess ())
      {
        // TODO determine the enduser ID of the outbound message
        // In many simple cases, this might be the sender's participant ID
        final String sEndUserID = aSenderID.getURIEncoded ();

        // TODO Enable Peppol Reporting when ready
        if (false)
          aBuilder.createAndStorePeppolReportingItemAfterSending (sEndUserID);

        // Send AE TDD for PINT-AE documents (as SENDER)
        if (sDocTypeID.contains ("@ae-1"))
        {
          final String sTDDTransportID = aSBDHIDHolder.isSet () ? aSBDHIDHolder.get () : UUID.randomUUID ().toString ();
          final Document aFinalDoc = aDoc;
          PhotonWorkerPool.getInstance ()
                          .run ("Send AE TDD (outbound)", () -> {
                            LOGGER.info ("Outbound TDD task starting for transport header: " + sTDDTransportID);
                            // Tell the ASP application the real outcome - "Reported to FTA"
                            // is only set when the TDD got a valid AS4 receipt
                            try
                            {
                              _sendOutboundTDD (aFinalDoc, sTDDTransportID);
                              TDDResultNotifier.notifyResult (TDDResultNotifier.DIRECTION_OUTBOUND,
                                                              sEndUserID,
                                                              aFinalDoc.getDocumentElement (),
                                                              sTDDTransportID,
                                                              true,
                                                              "SUBMIT TDD sent to " + APConfig.getAETDDReceiver ());
                            }
                            catch (final Throwable t)
                            {
                              LOGGER.error ("Failed to send outbound AE TDD", t);
                              TDDResultNotifier.notifyResult (TDDResultNotifier.DIRECTION_OUTBOUND,
                                                              sEndUserID,
                                                              aFinalDoc.getDocumentElement (),
                                                              sTDDTransportID,
                                                              false,
                                                              t.getMessage () != null ? t.getMessage ()
                                                                                      : t.getClass ().getName ());
                            }
                          });
        }
      }

      aSendingReport.setAS4SendingResult (eResult);

      if (aCaughtEx.isSet ())
      {
        final Phase4Exception ex = aCaughtEx.get ();
        LOGGER.error ("Error sending Peppol message via AS4", ex);
        aSendingReport.setAS4SendingException (ex);
        bExceptionCaught = true;
      }
    }
    catch (final Exception ex)
    {
      // Mostly errors on HTTP level
      LOGGER.error ("Error sending Peppol message via AS4", ex);
      aSendingReport.setAS4SendingException (ex);
      bExceptionCaught = true;
    }
    finally
    {
      aSW.stop ();
      aSendingReport.setOverallDurationMillis (aSW.getMillis ());
    }

    // Result may be null
    final boolean bSendingSuccess = eResult != null && eResult.isSuccess ();
    aSendingReport.setSendingSuccess (bSendingSuccess);
    aSendingReport.setOverallSuccess (bSendingSuccess && !bExceptionCaught);

    return aSendingReport;
  }

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

  private static void _sendOutboundTDD (@NonNull final Document aDoc,
                                        @NonNull final String sTransportHeaderID) throws Exception
  {
    final String sSeatID = APConfig.getMyPeppolSeatID ();
    final Element aUBLElement = aDoc.getDocumentElement ();

    final String sLocalName = aUBLElement.getLocalName ();
    final boolean bIsInvoice = "Invoice".equals (sLocalName);
    final boolean bIsCreditNote = "CreditNote".equals (sLocalName);
    if (!bIsInvoice && !bIsCreditNote)
    {
      throw new IllegalStateException ("Cannot send outbound TDD for unknown UBL element: " + sLocalName);
    }

    final byte [] aUBLBytes = XMLWriter.getNodeAsBytes (aUBLElement);
    if (aUBLBytes == null)
    {
      throw new IllegalStateException ("Failed to serialize UBL element for outbound TDD");
    }

    final String sTDDReceiverStr = APConfig.getAETDDReceiver ();
    final IParticipantIdentifier aTDDReceiver = new SimpleParticipantIdentifier ("iso6523-actorid-upis", sTDDReceiverStr);
    final IParticipantIdentifier aSenderID = PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme ("0235:" + sSeatID);

    final PeppolUAETDD10Builder aTDDBuilder = new PeppolUAETDD10Builder ().documentTypeCode (EUAETDDDocumentTypeCode.SUBMIT)
                                                                          .reporterRole (EUAETDDReporterRole.SENDER)
                                                                          .reportingParty (aSenderID)
                                                                          .receivingParty (new SimpleParticipantIdentifier ("iso6523-actorid-upis", sTDDReceiverStr))
                                                                          .reportersRepresentative (PeppolIdentifierFactory.INSTANCE.createParticipantIdentifierWithDefaultScheme ("0242:" + sSeatID.substring (3)));

    final InvoiceType [] aInvoiceRef = { null };
    final CreditNoteType [] aCreditNoteRef = { null };

    if (bIsInvoice)
    {
      aInvoiceRef[0] = UBL21Marshaller.invoice ().read (aUBLBytes);
      if (aInvoiceRef[0] != null)
      {
        aTDDBuilder.documentScope (EUAETDDDocumentScope.DOMESTIC)
                   .reportedTransaction (rt -> {
                     rt.transportHeaderID (sTransportHeaderID).initFromInvoice (aInvoiceRef[0]);
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
      }
    }
    else
    {
      aCreditNoteRef[0] = UBL21Marshaller.creditNote ().read (aUBLBytes);
      if (aCreditNoteRef[0] != null)
      {
        aTDDBuilder.documentScope (EUAETDDDocumentScope.DOMESTIC)
                   .reportedTransaction (rt -> {
                     rt.transportHeaderID (sTransportHeaderID).initFromCreditNote (aCreditNoteRef[0]);
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
      }
    }

    final TaxDataType aTDD = aTDDBuilder.build ();
    if (aTDD == null)
    {
      throw new IllegalStateException ("Failed to build outbound AE TDD - builder returned null");
    }

    final byte [] aTDDBytes = new PeppolUAETDD10Marshaller ().getAsBytes (aTDD);
    if (aTDDBytes == null)
    {
      throw new IllegalStateException ("Failed to serialize outbound AE TDD to bytes");
    }
    LOGGER.info ("Sending outbound AE TDD (" + aTDDBytes.length + " bytes) to " + aTDDReceiver.getURIEncoded ());

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
      throw new IllegalStateException ("Failed to re-parse outbound TDD XML for sending");
    }

    final Wrapper <Phase4Exception> aTDDEx = new Wrapper <> ();
    final EAS4UserMessageSendResult eTDDResult = Phase4PeppolSender.builder ()
                                                                   .httpClientFactory (aHCS)
                                                                   .documentTypeID (aTDDDocTypeID)
                                                                   .processID (aTDDProcessID)
                                                                   .senderParticipantID (aSenderID)
                                                                   .receiverParticipantID (aTDDReceiver)
                                                                   .senderPartyID (sSeatID)
                                                                   .countryC1 (APConfig.getMyPeppolCountryCode ())
                                                                   .payload (aTDDDoc.getDocumentElement ())
                                                                   .mlsType (EPeppolMLSType.ALWAYS_SEND)
                                                                   .mlsTo (new SimpleParticipantIdentifier ("iso6523-actorid-upis",
                                                                                                           "0242:" + sSeatID.substring (3)))
                                                                   .peppolAP_CAChecker (aCAChecker)
                                                                   .smpClient (aSMPClient)
                                                                   .sendMessageAndCheckForReceipt (aTDDEx::set);
    LOGGER.info ("Outbound AE TDD send result to " + aTDDReceiver.getURIEncoded () + ": " + eTDDResult);
    if (!eTDDResult.isSuccess ())
      throw new IllegalStateException ("Outbound AE TDD was not accepted by " +
                                       aTDDReceiver.getURIEncoded () +
                                       ": " +
                                       eTDDResult +
                                       (aTDDEx.isSet () ? " - " + aTDDEx.get ().getMessage () : ""),
                                       aTDDEx.get ());
  }

  /**
   * Send a Peppol message where the SBDH is passed in from the outside
   *
   * @param aData
   *        The Peppol SBDH data to be send
   * @param aSmlInfo
   *        The SML to be used for receiver lookup
   * @param aAPCAChecker
   *        The Peppol CA checker to be used.
   * @param aSendingReport
   *        The sending report to be filled.
   */
  static void sendPeppolMessagePredefinedSbdh (@NonNull final PeppolSBDHData aData,
                                               @NonNull final ISMLInfo aSmlInfo,
                                               @NonNull final TrustedCAChecker aAPCAChecker,
                                               @NonNull final Phase4PeppolSendingReport aSendingReport)
  {
    final String sMyPeppolSeatID = APConfig.getMyPeppolSeatID ();
    aSendingReport.setSenderPartyID (sMyPeppolSeatID);

    EAS4UserMessageSendResult eResult = null;
    boolean bExceptionCaught = false;
    final StopWatch aSW = StopWatch.createdStarted ();
    try
    {
      // Start configuring here
      final IParticipantIdentifier aReceiverID = aData.getReceiverAsIdentifier ();

      final SMPClientReadOnly aSMPClient = new SMPClientReadOnly (Phase4PeppolSender.URL_PROVIDER,
                                                                  aReceiverID,
                                                                  aSmlInfo);
      APConfig.applyHttpProxySettings (aSMPClient.httpClientSettings ());

      final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
      APConfig.applyHttpProxySettings (aHCS);

      final PeppolUserMessageSBDHBuilder aBuilder = Phase4PeppolSender.sbdhBuilder ()
                                                                      .httpClientFactory (aHCS)
                                                                      .payloadAndMetadata (aData)
                                                                      .senderPartyID (sMyPeppolSeatID)
                                                                      .peppolAP_CAChecker (aAPCAChecker)
                                                                      .smpClient (aSMPClient)
                                                                      .endpointURLConsumer (aSendingReport::setC3EndpointURL)
                                                                      .technicalContactConsumer (aSendingReport::setC3TechnicalContact)
                                                                      .certificateConsumer ( (aAPCertificate,
                                                                                              aCheckDT,
                                                                                              eCertCheckResult) -> {
                                                                        // Determined by SMP lookup
                                                                        aSendingReport.setC3Cert (aAPCertificate);
                                                                        aSendingReport.setC3CertCheckDT (aCheckDT);
                                                                        aSendingReport.setC3CertCheckResult (eCertCheckResult);
                                                                      })
                                                                      .sendingDateTimeConsumer (aSendingReport::setAS4SendingDT)
                                                                      .buildMessageCallback (new IAS4ClientBuildMessageCallback ()
                                                                      {
                                                                        public void onAS4Message (@NonNull final AbstractAS4Message <?> aMsg)
                                                                        {
                                                                          // Created AS4 fields
                                                                          final AS4UserMessage aUserMsg = (AS4UserMessage) aMsg;
                                                                          aSendingReport.setAS4MessageID (aUserMsg.getEbms3UserMessage ()
                                                                                                                  .getMessageInfo ()
                                                                                                                  .getMessageId ());
                                                                          aSendingReport.setAS4ConversationID (aUserMsg.getEbms3UserMessage ()
                                                                                                                       .getCollaborationInfo ()
                                                                                                                       .getConversationId ());
                                                                        }
                                                                      })
                                                                      .rawResponseConsumer (aSendingReport::setRawHttpResponse)
                                                                      .signalMsgConsumer ( (aSignalMsg,
                                                                                            aMessageMetadata,
                                                                                            aState) -> {
                                                                        aSendingReport.setAS4ReceivedSignalMsg (aSignalMsg);
                                                                      });
      final Wrapper <Phase4Exception> aCaughtEx = new Wrapper <> ();
      eResult = aBuilder.sendMessageAndCheckForReceipt (aCaughtEx::set);
      LOGGER.info ("Peppol client send result: " + eResult);

      if (eResult.isSuccess ())
      {
        // TODO determine the enduser ID of the outbound message
        // In many simple cases, this might be the sender's participant ID
        final String sEndUserID = aData.getSenderURIEncoded ();

        // TODO Enable Peppol Reporting when ready
        if (false)
          aBuilder.createAndStorePeppolReportingItemAfterSending (sEndUserID);
      }

      aSendingReport.setAS4SendingResult (eResult);

      if (aCaughtEx.isSet ())
      {
        final Phase4Exception ex = aCaughtEx.get ();
        LOGGER.error ("Error sending Peppol message via AS4", ex);
        aSendingReport.setAS4SendingException (ex);
        bExceptionCaught = true;
      }
    }
    catch (final Exception ex)
    {
      // Mostly errors on HTTP level
      LOGGER.error ("Error sending Peppol message via AS4", ex);
      aSendingReport.setAS4SendingException (ex);
      bExceptionCaught = true;
    }
    finally
    {
      aSW.stop ();
      aSendingReport.setOverallDurationMillis (aSW.getMillis ());
    }

    // Result may be null
    final boolean bSendingSuccess = eResult != null && eResult.isSuccess ();
    aSendingReport.setSendingSuccess (bSendingSuccess);
    aSendingReport.setOverallSuccess (bSendingSuccess && !bExceptionCaught);
  }
}
