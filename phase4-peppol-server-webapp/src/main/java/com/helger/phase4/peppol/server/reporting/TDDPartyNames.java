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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.helger.peppol.uae.tdd.v100.ReportedDocumentType;
import com.helger.peppol.uae.tdd.v100.ReportedTransactionType;
import com.helger.peppol.uae.tdd.v100.TaxDataType;
import com.helger.phase4.logging.Phase4LoggerFactory;

import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyLegalEntityType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

/**
 * Adds the seller name (ibt-027) and the buyer name (ibt-044) to the reported
 * document of an AE Tax Data Document.<br>
 * The AE TDD rules 1.0.5 (ibr-tdd-58 to ibr-tdd-61) require exactly one
 * <code>cac:PartyLegalEntity</code> holding only <code>cbc:RegistrationName</code>
 * in both parties. The TDD builder of <code>peppol-uae-tdd</code> 1.0.0 does not
 * copy the names from the invoice, so they are added here after building.
 */
public final class TDDPartyNames
{
  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (TDDPartyNames.class);

  private TDDPartyNames ()
  {}

  @Nullable
  private static String _getRegistrationName (@Nullable final PartyType aParty)
  {
    if (aParty != null)
      for (final PartyLegalEntityType aPLE : aParty.getPartyLegalEntity ())
      {
        final String sName = aPLE.getRegistrationNameValue ();
        if (sName != null && !sName.isBlank ())
          return sName.trim ();
      }
    return null;
  }

  private static void _setRegistrationName (@Nullable final PartyType aTDDParty,
                                            @Nullable final String sName,
                                            @NonNull final String sWhat)
  {
    if (aTDDParty == null)
      return;
    if (sName == null)
    {
      LOGGER.warn ("AE TDD: the source document has no " + sWhat + " registration name");
      return;
    }
    // Exactly one PartyLegalEntity that contains only the RegistrationName
    aTDDParty.getPartyLegalEntity ().clear ();
    final PartyLegalEntityType aPLE = new PartyLegalEntityType ();
    aPLE.setRegistrationName (sName);
    aTDDParty.addPartyLegalEntity (aPLE);
  }

  private static void _apply (@Nullable final TaxDataType aTDD,
                              @Nullable final String sSellerName,
                              @Nullable final String sBuyerName)
  {
    if (aTDD == null)
      return;
    for (final ReportedTransactionType aRT : aTDD.getReportedTransaction ())
    {
      final ReportedDocumentType aRD = aRT.getReportedDocument ();
      if (aRD == null)
        continue;
      if (aRD.getAccountingSupplierParty () != null)
        _setRegistrationName (aRD.getAccountingSupplierParty ().getParty (), sSellerName, "seller");
      if (aRD.getAccountingCustomerParty () != null)
        _setRegistrationName (aRD.getAccountingCustomerParty ().getParty (), sBuyerName, "buyer");
    }
  }

  /**
   * Copy the seller and buyer names of the invoice into the TDD.
   *
   * @param aTDD
   *        The built TDD. May be <code>null</code>.
   * @param aInvoice
   *        The source invoice. May be <code>null</code>.
   */
  public static void applyFromInvoice (@Nullable final TaxDataType aTDD, @Nullable final InvoiceType aInvoice)
  {
    if (aInvoice == null)
      return;
    _apply (aTDD,
            _getRegistrationName (aInvoice.getAccountingSupplierParty () == null ? null
                                                                                 : aInvoice.getAccountingSupplierParty ()
                                                                                           .getParty ()),
            _getRegistrationName (aInvoice.getAccountingCustomerParty () == null ? null
                                                                                 : aInvoice.getAccountingCustomerParty ()
                                                                                           .getParty ()));
  }

  /**
   * Copy the seller and buyer names of the credit note into the TDD.
   *
   * @param aTDD
   *        The built TDD. May be <code>null</code>.
   * @param aCreditNote
   *        The source credit note. May be <code>null</code>.
   */
  public static void applyFromCreditNote (@Nullable final TaxDataType aTDD, @Nullable final CreditNoteType aCreditNote)
  {
    if (aCreditNote == null)
      return;
    _apply (aTDD,
            _getRegistrationName (aCreditNote.getAccountingSupplierParty () == null ? null
                                                                                    : aCreditNote.getAccountingSupplierParty ()
                                                                                                 .getParty ()),
            _getRegistrationName (aCreditNote.getAccountingCustomerParty () == null ? null
                                                                                    : aCreditNote.getAccountingCustomerParty ()
                                                                                                 .getParty ()));
  }
}
