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
 * The AE Tax Data Document built for a sent invoice or credit note must pass
 * the validation that runs before it is sent, for every type the sender uses:
 * Submit, Resubmit and Withdraw.
 */
public final class PeppolSenderTDDBuildTest
{
  private static final String TRANSPORT_ID = "6f386b28-f4a3-4d74-bfbb-31668bd153b7";

  private static Document _read (final String sName) throws Exception
  {
    try (final InputStream aIS = PeppolSenderTDDBuildTest.class.getResourceAsStream ("/external/ae-samples/" + sName))
    {
      assertNotNull ("Test file " + sName + " not found", aIS);
      final Document aDoc = DOMReader.readXMLDOM (aIS);
      assertNotNull (aDoc);
      return aDoc;
    }
  }

  private static void _buildAndValidate (final String sName, final EUAETDDDocumentTypeCode eType) throws Exception
  {
    final byte [] aTDD = PeppolSender.buildOutboundTDD (_read (sName), TRANSPORT_ID, eType);
    assertNotNull (aTDD);
    final String sTDD = new String (aTDD, StandardCharsets.UTF_8);

    // The type code and the reference to the invoice transmission are in the document
    assertTrue (sName + "/" + eType + ": type code missing in\n" + sTDD,
                sTDD.contains (">" + eType.getID () + "</"));
    assertTrue (sName + "/" + eType + ": transport header ID missing", sTDD.contains (TRANSPORT_ID));

    final List <String> aErrors = TDDValidation.getErrors (aTDD);
    assertEquals (sName + "/" + eType + " is not valid: " + aErrors + "\n" + sTDD, 0, aErrors.size ());
  }

  @Test
  public void testInvoice () throws Exception
  {
    for (final EUAETDDDocumentTypeCode eType : new EUAETDDDocumentTypeCode [] { EUAETDDDocumentTypeCode.SUBMIT,
                                                                                EUAETDDDocumentTypeCode.RESUBMIT,
                                                                                EUAETDDDocumentTypeCode.WITHDRAW })
      _buildAndValidate ("pint-ae-invoice.xml", eType);
  }

  @Test
  public void testCreditNote () throws Exception
  {
    for (final EUAETDDDocumentTypeCode eType : new EUAETDDDocumentTypeCode [] { EUAETDDDocumentTypeCode.SUBMIT,
                                                                                EUAETDDDocumentTypeCode.RESUBMIT,
                                                                                EUAETDDDocumentTypeCode.WITHDRAW })
      _buildAndValidate ("pint-ae-creditnote.xml", eType);
  }

  @Test
  public void testInvalidTDDIsDetected ()
  {
    // Well-formed XML that is not a Tax Data Document at all
    final List <String> aErrors = TDDValidation.getErrors ("<TaxData xmlns=\"urn:peppol:schema:taxdata:1.0\"/>".getBytes (StandardCharsets.UTF_8));
    assertTrue ("An empty TaxData element must fail the validation", aErrors.size () > 0);
  }
}
