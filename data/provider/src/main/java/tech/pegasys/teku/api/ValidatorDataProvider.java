/*
 * Copyright 2020 ConsenSys AG.
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

package tech.pegasys.teku.api;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import it.unimi.dsi.fastutil.ints.IntIterable;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.api.exceptions.BadRequestException;
import tech.pegasys.teku.api.request.v1.validator.BeaconCommitteeSubscriptionRequest;
import tech.pegasys.teku.api.response.v1.validator.PostSyncDutiesResponse;
import tech.pegasys.teku.api.schema.BLSPubKey;
import tech.pegasys.teku.api.schema.SignedBeaconBlock;
import tech.pegasys.teku.api.schema.ValidatorBlockResult;
import tech.pegasys.teku.api.schema.altair.SignedBeaconBlockAltair;
import tech.pegasys.teku.api.schema.altair.SignedContributionAndProof;
import tech.pegasys.teku.api.schema.altair.SyncCommitteeSubnetSubscription;
import tech.pegasys.teku.api.schema.bellatrix.SignedBeaconBlockBellatrix;
import tech.pegasys.teku.api.schema.bellatrix.SignedBlindedBeaconBlockBellatrix;
import tech.pegasys.teku.api.schema.phase0.SignedBeaconBlockPhase0;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitvector;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.provider.JsonProvider;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.SignedValidatorRegistration;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.ContributionAndProof;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncCommitteeContribution;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncCommitteeContributionSchema;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncCommitteeMessage;
import tech.pegasys.teku.spec.datastructures.operations.versions.bellatrix.BeaconPreparableProposer;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult.FailureReason;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsAltair;
import tech.pegasys.teku.storage.client.ChainDataUnavailableException;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.validator.api.AttesterDuties;
import tech.pegasys.teku.validator.api.CommitteeSubscriptionRequest;
import tech.pegasys.teku.validator.api.ProposerDuties;
import tech.pegasys.teku.validator.api.SendSignedBlockResult;
import tech.pegasys.teku.validator.api.SubmitDataError;
import tech.pegasys.teku.validator.api.SyncCommitteeDuty;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;

public class ValidatorDataProvider {
  private static final Logger LOG = LogManager.getLogger();

  public static final String CANNOT_PRODUCE_HISTORIC_BLOCK =
      "Cannot produce a block for a historic slot.";
  public static final String NO_SLOT_PROVIDED = "No slot was provided.";
  public static final String NO_RANDAO_PROVIDED = "No randao_reveal was provided.";
  public static final String PARTIAL_PUBLISH_FAILURE_MESSAGE =
      "Some items failed to publish, refer to errors for details";
  private final ValidatorApiChannel validatorApiChannel;
  private final CombinedChainDataClient combinedChainDataClient;

  private static final int SC_INTERNAL_ERROR = 500;
  private static final int SC_ACCEPTED = 202;
  private static final int SC_OK = 200;
  private final Spec spec;

  public ValidatorDataProvider(
      final Spec spec,
      final ValidatorApiChannel validatorApiChannel,
      final CombinedChainDataClient combinedChainDataClient) {
    this.validatorApiChannel = validatorApiChannel;
    this.combinedChainDataClient = combinedChainDataClient;
    this.spec = spec;
  }

  public boolean isStoreAvailable() {
    return combinedChainDataClient.isStoreAvailable();
  }

  public SafeFuture<Optional<BeaconBlock>> getUnsignedBeaconBlockAtSlot(
      final UInt64 slot,
      final BLSSignature randao,
      final Optional<Bytes32> graffiti,
      final boolean isBlinded) {
    if (slot == null) {
      throw new IllegalArgumentException(NO_SLOT_PROVIDED);
    }
    if (randao == null) {
      throw new IllegalArgumentException(NO_RANDAO_PROVIDED);
    }
    final int slotsPerEpoch = spec.atSlot(slot).getConfig().getSlotsPerEpoch();
    final UInt64 currentSlot = combinedChainDataClient.getCurrentSlot();
    if (currentSlot.plus(slotsPerEpoch).isLessThan(slot)) {
      throw new IllegalArgumentException(
          "Cannot produce a block more than " + slotsPerEpoch + " slots in the future.");
    }
    if (currentSlot.isGreaterThan(slot)) {
      throw new IllegalArgumentException(CANNOT_PRODUCE_HISTORIC_BLOCK);
    }
    return validatorApiChannel.createUnsignedBlock(slot, randao, graffiti, isBlinded);
  }

  public SafeFuture<Optional<BeaconBlock>> getUnsignedBeaconBlockAtSlot(
      UInt64 slot, BLSSignature randao, Optional<Bytes32> graffiti) {
    if (randao == null) {
      throw new IllegalArgumentException(NO_RANDAO_PROVIDED);
    }
    return getUnsignedBeaconBlockAtSlot(
        slot, BLSSignature.fromBytesCompressed(randao.toSSZBytes()), graffiti, false);
  }

  public SpecMilestone getMilestoneAtSlot(final UInt64 slot) {
    return spec.atSlot(slot).getMilestone();
  }

  public SafeFuture<Optional<AttestationData>> createAttestationDataAtSlot(
      UInt64 slot, int committeeIndex) {
    if (!isStoreAvailable()) {
      return SafeFuture.failedFuture(new ChainDataUnavailableException());
    }
    return validatorApiChannel
        .createAttestationData(slot, committeeIndex)
        .thenApply(maybeAttestation -> maybeAttestation)
        .exceptionallyCompose(
            error -> {
              final Throwable rootCause = Throwables.getRootCause(error);
              if (rootCause instanceof IllegalArgumentException) {
                return SafeFuture.failedFuture(new BadRequestException(rootCause.getMessage()));
              }
              return SafeFuture.failedFuture(error);
            });
  }

  public SafeFuture<List<SubmitDataError>> submitAttestations(List<Attestation> attestations) {
    return validatorApiChannel.sendSignedAttestations(attestations);
  }

  public SignedBeaconBlock parseBlock(final JsonProvider jsonProvider, final String jsonBlock)
      throws JsonProcessingException {
    final ObjectMapper mapper = jsonProvider.getObjectMapper();
    final JsonNode jsonNode = mapper.readTree(jsonBlock);
    final UInt64 slot = mapper.treeToValue(jsonNode.findValue("slot"), UInt64.class);
    final SignedBeaconBlock signedBeaconBlock;
    checkNotNull(slot, "Slot was not found in json block");
    switch (spec.atSlot(slot).getMilestone()) {
      case PHASE0:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockPhase0.class);
        break;
      case ALTAIR:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockAltair.class);
        break;
      case BELLATRIX:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockBellatrix.class);
        break;
      default:
        throw new IllegalArgumentException("Could not determine milestone for slot " + slot);
    }
    return signedBeaconBlock;
  }

  public SignedBeaconBlock parseBlindedBlock(
      final JsonProvider jsonProvider, final String jsonBlock) throws JsonProcessingException {
    final ObjectMapper mapper = jsonProvider.getObjectMapper();
    final JsonNode jsonNode = mapper.readTree(jsonBlock);
    final UInt64 slot = mapper.treeToValue(jsonNode.findValue("slot"), UInt64.class);
    final SignedBeaconBlock signedBlindedBlock;
    checkNotNull(slot, "Slot was not found in json block");
    switch (spec.atSlot(slot).getMilestone()) {
      case PHASE0:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockPhase0.class);
        break;
      case ALTAIR:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockAltair.class);
        break;
      case BELLATRIX:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBlindedBeaconBlockBellatrix.class);
        break;
      default:
        throw new IllegalArgumentException("Could not determine milestone for slot " + slot);
    }
    return signedBlindedBlock;
  }

  public SafeFuture<ValidatorBlockResult> submitSignedBlock(
      final SignedBeaconBlock signedBeaconBlock) {
    return submitSignedBlock(signedBeaconBlock.asInternalSignedBeaconBlock(spec))
        .thenApply(ValidatorDataProvider::generateSubmitSignedBlockResponse);
  }

  public SafeFuture<SendSignedBlockResult> submitSignedBlock(
      final tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock signedBeaconBlock) {
    return validatorApiChannel.sendSignedBlock(signedBeaconBlock);
  }

  public SafeFuture<SendSignedBlockResult> submitSignedBlindedBlock(
      final tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock signedBeaconBlock) {
    LOG.debug("parsed block is from slot: {}", signedBeaconBlock.getMessage().getSlot());
    return validatorApiChannel.sendSignedBlock(signedBeaconBlock);
  }

  public SafeFuture<List<SubmitDataError>> submitCommitteeSignatures(
      final List<SyncCommitteeMessage> messages) {
    return validatorApiChannel.sendSyncCommitteeMessages(
        messages.stream()
            .flatMap(message -> checkInternalCommitteeSignature(message).stream())
            .collect(Collectors.toList()));
  }

  private Optional<SyncCommitteeMessage> checkInternalCommitteeSignature(
      final SyncCommitteeMessage message) {
    final Optional<SchemaDefinitionsAltair> schema =
        spec.atSlot(message.getSlot()).getSchemaDefinitions().toVersionAltair();
    if (schema.isEmpty()) {
      final String errorMessage =
          String.format(
              "Could not create sync committee signature at phase0 slot %s for validator %s",
              message.getSlot(), message.getValidatorIndex());
      throw new IllegalArgumentException(errorMessage);
    }
    return Optional.of(message);
  }

  public SafeFuture<Optional<Attestation>> createAggregate(
      final UInt64 slot, final Bytes32 attestationHashTreeRoot) {
    return validatorApiChannel.createAggregate(slot, attestationHashTreeRoot);
  }

  public SafeFuture<List<SubmitDataError>> sendAggregateAndProofs(
      List<SignedAggregateAndProof> aggregateAndProofs) {
    return validatorApiChannel.sendAggregateAndProofs(aggregateAndProofs);
  }

  public void subscribeToBeaconCommittee(final List<BeaconCommitteeSubscriptionRequest> requests) {
    validatorApiChannel.subscribeToBeaconCommittee(
        requests.stream()
            .map(
                request ->
                    new CommitteeSubscriptionRequest(
                        request.validator_index,
                        request.committee_index,
                        request.committees_at_slot,
                        request.slot,
                        request.is_aggregator))
            .collect(toList()));
  }

  public void subscribeToSyncCommitteeSubnets(
      final Collection<SyncCommitteeSubnetSubscription> subscriptions) {
    validatorApiChannel.subscribeToSyncCommitteeSubnets(
        subscriptions.stream()
            .map(
                subscription ->
                    new tech.pegasys.teku.validator.api.SyncCommitteeSubnetSubscription(
                        subscription.validatorIndex.intValue(),
                        IntOpenHashSet.toSet(
                            subscription.syncCommitteeIndices.stream().mapToInt(UInt64::intValue)),
                        subscription.untilEpoch))
            .collect(toList()));
  }

  public SafeFuture<Optional<AttesterDuties>> getAttesterDuties(
      final UInt64 epoch, final IntList indices) {
    return SafeFuture.of(() -> validatorApiChannel.getAttestationDuties(epoch, indices));
  }

  public SafeFuture<Optional<ProposerDuties>> getProposerDuties(final UInt64 epoch) {
    return SafeFuture.of(() -> validatorApiChannel.getProposerDuties(epoch));
  }

  public SafeFuture<Optional<SyncCommitteeContribution>> createSyncCommitteeContribution(
      final UInt64 slot, final int subcommitteeIndex, final Bytes32 blockRoot) {
    return validatorApiChannel.createSyncCommitteeContribution(slot, subcommitteeIndex, blockRoot);
  }

  public SafeFuture<Optional<PostSyncDutiesResponse>> getSyncDuties(
      final UInt64 epoch, final IntList indices) {
    return SafeFuture.of(() -> validatorApiChannel.getSyncCommitteeDuties(epoch, indices))
        .thenApply(
            res ->
                res.map(
                    duties ->
                        new PostSyncDutiesResponse(
                            duties.getDuties().stream()
                                .filter(duty -> duty.getPublicKey() != null)
                                .map(this::mapToSyncCommitteeDuty)
                                .collect(toList()))));
  }

  private tech.pegasys.teku.api.response.v1.validator.SyncCommitteeDuty mapToSyncCommitteeDuty(
      final SyncCommitteeDuty duty) {
    return new tech.pegasys.teku.api.response.v1.validator.SyncCommitteeDuty(
        new BLSPubKey(duty.getPublicKey().toBytesCompressed()),
        UInt64.valueOf(duty.getValidatorIndex()),
        duty.getValidatorSyncCommitteeIndices());
  }

  public SafeFuture<Void> sendContributionAndProofs(
      final List<SignedContributionAndProof> contributionAndProofs) {
    return validatorApiChannel.sendSignedContributionAndProofs(
        contributionAndProofs.stream()
            .map(this::asInternalContributionAndProofs)
            .collect(toList()));
  }

  public void prepareBeaconProposer(List<BeaconPreparableProposer> beaconPreparableProposers) {
    validatorApiChannel.prepareBeaconProposer(beaconPreparableProposers);
  }

  public SafeFuture<Void> registerValidators(
      SszList<SignedValidatorRegistration> validatorRegistrations) {
    return validatorApiChannel.registerValidators(validatorRegistrations);
  }

  public boolean isPhase0Slot(final UInt64 slot) {
    return spec.atSlot(slot).getMilestone() == SpecMilestone.PHASE0;
  }

  private tech.pegasys.teku.spec.datastructures.operations.versions.altair
          .SignedContributionAndProof
      asInternalContributionAndProofs(final SignedContributionAndProof signedContributionAndProof) {
    final UInt64 slot = signedContributionAndProof.message.contribution.slot;
    final Bytes32 root = signedContributionAndProof.message.contribution.beaconBlockRoot;
    final UInt64 subcommitteeIndex =
        signedContributionAndProof.message.contribution.subcommitteeIndex;
    final IntIterable indices =
        getAggregationBits(signedContributionAndProof.message.contribution.aggregationBits, slot);

    final BLSSignature signature =
        signedContributionAndProof.message.contribution.signature.asInternalBLSSignature();
    final SyncCommitteeContribution contribution =
        spec.getSyncCommitteeUtilRequired(slot)
            .createSyncCommitteeContribution(slot, root, subcommitteeIndex, indices, signature);

    final ContributionAndProof message =
        spec.getSyncCommitteeUtilRequired(slot)
            .createContributionAndProof(
                signedContributionAndProof.message.aggregatorIndex,
                contribution,
                signedContributionAndProof.message.selectionProof.asInternalBLSSignature());

    return spec.getSyncCommitteeUtilRequired(slot)
        .createSignedContributionAndProof(
            message, signedContributionAndProof.signature.asInternalBLSSignature());
  }

  private IntIterable getAggregationBits(final Bytes aggregationBits, final UInt64 slot) {
    final SchemaDefinitionsAltair altairDefinitions =
        SchemaDefinitionsAltair.required(spec.atSlot(slot).getSchemaDefinitions());
    final SyncCommitteeContributionSchema syncCommitteeContributionSchema =
        altairDefinitions.getSyncCommitteeContributionSchema();
    final SszBitvector aggregationBitsVector =
        syncCommitteeContributionSchema.getAggregationBitsSchema().fromBytes(aggregationBits);

    return aggregationBitsVector.getAllSetBits();
  }

  private static ValidatorBlockResult generateSubmitSignedBlockResponse(
      SendSignedBlockResult result) {
    int responseCode;
    Optional<Bytes32> hashRoot = result.getBlockRoot();
    if (result.getRejectionReason().isEmpty()) {
      responseCode = SC_OK;
    } else if (result.getRejectionReason().get().equals(FailureReason.INTERNAL_ERROR.name())) {
      responseCode = SC_INTERNAL_ERROR;
    } else {
      responseCode = SC_ACCEPTED;
    }

    return new ValidatorBlockResult(responseCode, result.getRejectionReason(), hashRoot);
  }
}
