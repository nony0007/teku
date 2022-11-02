/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.beaconrestapi.handlers.v1.beacon;

import tech.pegasys.teku.beaconrestapi.AbstractPostBlockTest;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiEndpoint;

public class PostBlindedBlockTest extends AbstractPostBlockTest {
  @Override
  public RestApiEndpoint getHandler() {
    return new PostBlindedBlock(
        validatorDataProvider, syncDataProvider, spec, schemaDefinitionCache);
  }

  @Override
  public boolean isBlinded() {
    return true;
  }
}
