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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import com.helger.io.resource.inmemory.ReadableResourceByteArray;
import com.helger.peppol.uae.tdd.validate.PeppolUAETDDValidator;
import com.helger.phase4.logging.Phase4LoggerFactory;
import com.helger.phase4.peppol.server.APConfig;
import com.helger.schematron.ISchematronResource;
import com.helger.schematron.svrl.SVRLFailedAssert;
import com.helger.schematron.svrl.SVRLHelper;
import com.helger.schematron.svrl.jaxb.SchematronOutputType;

/**
 * Validation of an AE Tax Data Document before it is sent. The UAE solution
 * architecture requires the sending and the receiving Service Provider to
 * validate the TDD they created ("Generate UUID, construct UAE TDD, validate UAE
 * TDD").
 */
public final class TDDValidation
{
  /**
   * An error message that starts with this text tells the ASP application that
   * the TDD itself is invalid: sending the same content again cannot succeed.
   */
  public static final String ERROR_PREFIX = "TDD_INVALID";

  private static final Logger LOGGER = Phase4LoggerFactory.getLogger (TDDValidation.class);

  private TDDValidation ()
  {}

  /**
   * @param aTDDBytes
   *        The serialized Tax Data Document
   * @return The texts of all failed rules with the severity "error". Empty if
   *         the document is valid.
   */
  @NonNull
  public static List <String> getErrors (final byte @NonNull [] aTDDBytes)
  {
    final List <String> ret = new ArrayList <> ();
    try
    {
      final ISchematronResource aSchematron = PeppolUAETDDValidator.getSchematronUAE_TDD_10 ();
      final SchematronOutputType aSVRL = aSchematron.applySchematronValidationToSVRL (new ReadableResourceByteArray (aTDDBytes));
      if (aSVRL == null)
      {
        ret.add ("The Tax Data Document could not be read by the validation");
        return ret;
      }
      for (final SVRLFailedAssert aFailed : SVRLHelper.getAllFailedAssertions (aSVRL))
        if (aFailed.getFlag ().isError ())
          ret.add (aFailed.getText ());
    }
    catch (final Exception ex)
    {
      LOGGER.error ("The validation of the AE TDD could not be executed", ex);
      ret.add ("The validation could not be executed: " + ex.getMessage ());
    }
    return ret;
  }

  /**
   * Stop an invalid Tax Data Document from being sent.
   *
   * @param aTDDBytes
   *        The serialized Tax Data Document
   * @throws IllegalStateException
   *         if at least one rule failed. The message starts with
   *         {@link #ERROR_PREFIX}.
   */
  public static void assertValid (final byte @NonNull [] aTDDBytes)
  {
    if (!APConfig.isAETDDValidationEnabled ())
      return;

    final List <String> aErrors = getErrors (aTDDBytes);
    if (aErrors.isEmpty ())
    {
      LOGGER.info ("The AE TDD passed the validation");
      return;
    }

    LOGGER.error ("The AE TDD failed the validation with " + aErrors.size () + " error(s) and is not sent: " + aErrors);
    final int nMax = Math.min (aErrors.size (), 5);
    throw new IllegalStateException (ERROR_PREFIX +
                                     ": " +
                                     String.join (" | ", aErrors.subList (0, nMax)) +
                                     (aErrors.size () > nMax ? " (+" + (aErrors.size () - nMax) + " more)" : ""));
  }
}
