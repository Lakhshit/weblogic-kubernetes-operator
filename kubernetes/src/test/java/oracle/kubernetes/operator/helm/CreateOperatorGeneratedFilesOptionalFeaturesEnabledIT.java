// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helm;

import oracle.kubernetes.operator.create.CreateOperatorGeneratedFilesOptionalFeaturesEnabledTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CreateOperatorGeneratedFilesOptionalFeaturesEnabledIT
    extends CreateOperatorGeneratedFilesOptionalFeaturesEnabledTestBase {

  @BeforeAll
  public static void setup() throws Exception {
    defineOperatorYamlFactory(new HelmOperatorYamlFactory());
  }

  @Test
  @Override
  public void generatesCorrect_weblogicOperatorNamespace() {
    // the user is responsible for creating the namespace
  }

  @Test
  @Override
  public void generatesCorrect_weblogicOperatorServiceAccount() {
    // the user is responsible for creating the service account
  }
}
