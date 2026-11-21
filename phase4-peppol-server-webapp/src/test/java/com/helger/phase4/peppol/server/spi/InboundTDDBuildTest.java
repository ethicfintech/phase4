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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;
import org.w3c.dom.Document;

import com.helger.peppol.uae.tdd.codelist.EUAETDDDocumentTypeCode;
import com.helger.phase4.peppol.server.reporting.TDDValidation;
import com.helger.xml.serialize.read.DOMReader;

/**
 * The AE Tax Data Document built for a RECEIVED invoice or credit note must
 * pass the validation that runs before it is sent: the report (Submit or
 * Resubmit) for a valid document, and the FAILED report (Tax Data Status) for a
 * document that did not pass validation.
 */
public final class InboundTDDBuildTest
{
  private static final String TRANSPORT_ID = "6f386b28-f4a3-4d74-bfbb-31668bd153b7";

  private static Document _read (final String sName) throws Exception
  {
    try (final InputStream aIS = InboundTDDBuildTest.class.getResourceAsStream ("/external/ae-samples/" + sName))
    {
      assertNotNull ("Test file " + sName + " not found", aIS);
      final Document aDoc = DOMReader.readXMLDOM (aIS);
      assertNotNull (aDoc);
      return aDoc;
    }
  }

  private static void _buildAndValidate (final String sName,
                                         final boolean bValidationFailed,
                                         final boolean bResubmit,
                                         final EUAETDDDocumentTypeCode eExpectedType) throws Exception
  {
    final EUAETDDDocumentTypeCode [] aType = new EUAETDDDocumentTypeCode [1];
    final byte [] aTDD = StoringPeppolIncomingSBDHandlerSPI.buildInboundTDD (_read (sName).getDocumentElement (),
                                                                             TRANSPORT_ID,
                                                                             bValidationFailed,
                                                                             bResubmit,
                                                                             aType);
    assertNotNull (aTDD);
    final String sTDD = new String (aTDD, StandardCharsets.UTF_8);
    final String sCase = sName + " (failed=" + bValidationFailed + ", resubmit=" + bResubmit + ")";

    assertEquals (sCase + ": type", eExpectedType, aType[0]);
    assertTrue (sCase + ": type code missing in\n" + sTDD, sTDD.contains (">" + eExpectedType.getID () + "</"));
    assertTrue (sCase + ": transport header ID missing", sTDD.contains (TRANSPORT_ID));
    // Reported in the role of the receiver
    assertTrue (sCase + ": reporter role 02 missing in\n" + sTDD, sTDD.contains (">02</"));

    final List <String> aErrors = TDDValidation.getErrors (aTDD);
    assertEquals (sCase + " is not valid: " + aErrors + "\n" + sTDD, 0, aErrors.size ());
  }

  @Test
  public void testValidInvoice () throws Exception
  {
    _buildAndValidate ("pint-ae-invoice.xml", false, false, EUAETDDDocumentTypeCode.SUBMIT);
    _buildAndValidate ("pint-ae-invoice.xml", false, true, EUAETDDDocumentTypeCode.RESUBMIT);
  }

  @Test
  public void testValidCreditNote () throws Exception
  {
    _buildAndValidate ("pint-ae-creditnote.xml", false, false, EUAETDDDocumentTypeCode.SUBMIT);
    _buildAndValidate ("pint-ae-creditnote.xml", false, true, EUAETDDDocumentTypeCode.RESUBMIT);
  }

  @Test
  public void testDocumentThatFailedValidation () throws Exception
  {
    // A valid document that is reported as FAILED on request
    _buildAndValidate ("pint-ae-invoice.xml", true, false, EUAETDDDocumentTypeCode.FAILED);
    _buildAndValidate ("pint-ae-creditnote.xml", true, false, EUAETDDDocumentTypeCode.FAILED);
    // The invalid invoice the Peppol testbed sends in its "invalid invoice reception" case
    _buildAndValidate ("pint-ae-invoice-invalid.xml", true, false, EUAETDDDocumentTypeCode.FAILED);
  }
}
