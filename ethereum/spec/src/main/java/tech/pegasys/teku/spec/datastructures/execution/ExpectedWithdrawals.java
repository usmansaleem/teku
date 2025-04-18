/*
 * Copyright Consensys Software Inc., 2025
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

package tech.pegasys.teku.spec.datastructures.execution;

import static tech.pegasys.teku.spec.config.SpecConfig.FAR_FUTURE_EPOCH;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.bytes.Bytes20;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszUInt64List;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.config.SpecConfigCapella;
import tech.pegasys.teku.spec.config.SpecConfigElectra;
import tech.pegasys.teku.spec.datastructures.execution.versions.capella.Withdrawal;
import tech.pegasys.teku.spec.datastructures.execution.versions.capella.WithdrawalSchema;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.MutableBeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.capella.BeaconStateCapella;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.capella.MutableBeaconStateCapella;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.electra.BeaconStateElectra;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.electra.MutableBeaconStateElectra;
import tech.pegasys.teku.spec.datastructures.state.versions.electra.PendingPartialWithdrawal;
import tech.pegasys.teku.spec.logic.common.helpers.BeaconStateMutators;
import tech.pegasys.teku.spec.logic.common.helpers.MiscHelpers;
import tech.pegasys.teku.spec.logic.common.helpers.Predicates;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.BlockProcessingException;
import tech.pegasys.teku.spec.logic.versions.electra.helpers.MiscHelpersElectra;
import tech.pegasys.teku.spec.logic.versions.electra.helpers.PredicatesElectra;
import tech.pegasys.teku.spec.schemas.SchemaDefinitions;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsCapella;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsElectra;

public class ExpectedWithdrawals {
  private static final Logger LOG = LogManager.getLogger();
  public static final ExpectedWithdrawals NOOP = new ExpectedWithdrawals(List.of(), 0);
  private final List<Withdrawal> withdrawalList;
  private Optional<SszList<Withdrawal>> maybeWithdrawalsSszList = Optional.empty();
  private final int partialWithdrawalCount;

  private ExpectedWithdrawals(
      final List<Withdrawal> withdrawalList, final int partialWithdrawalCount) {
    LOG.debug(
        "Expected withdrawals created with withdrawals list size {}, partials {}",
        withdrawalList == null ? -1 : withdrawalList.size(),
        partialWithdrawalCount);
    this.withdrawalList = withdrawalList;
    this.partialWithdrawalCount = partialWithdrawalCount;
  }

  private ExpectedWithdrawals(
      final List<Withdrawal> withdrawalList,
      final int partialWithdrawalCount,
      final SchemaDefinitionsCapella schemaDefinitions) {
    this.withdrawalList = withdrawalList;
    this.partialWithdrawalCount = partialWithdrawalCount;
    LOG.debug(
        "Expected withdrawals created with withdrawals list size {}, partials {}, schema definition class: {}",
        withdrawalList == null ? -1 : withdrawalList.size(),
        partialWithdrawalCount,
        schemaDefinitions.getClass());

    getExpectedWithdrawalsSszList(schemaDefinitions);
  }

  public static ExpectedWithdrawals create(
      final BeaconState preState,
      final SchemaDefinitions schemaDefinitions,
      final MiscHelpers miscHelpers,
      final SpecConfig specConfig,
      final Predicates predicates) {

    if (preState.toVersionElectra().isPresent()) {
      return createFromElectraState(
          BeaconStateElectra.required(preState),
          SchemaDefinitionsElectra.required(schemaDefinitions),
          MiscHelpersElectra.required(miscHelpers),
          SpecConfigElectra.required(specConfig),
          PredicatesElectra.required(predicates));
    } else if (preState.toVersionCapella().isPresent()) {
      return createFromCapellaState(
          BeaconStateCapella.required(preState),
          SchemaDefinitionsCapella.required(schemaDefinitions),
          miscHelpers,
          SpecConfigCapella.required(specConfig),
          predicates);
    }

    return NOOP;
  }

  private static ExpectedWithdrawals createFromCapellaState(
      final BeaconStateCapella preState,
      final SchemaDefinitionsCapella schemaDefinitionsCapella,
      final MiscHelpers miscHelpers,
      final SpecConfigCapella specConfigCapella,
      final Predicates predicates) {
    final List<Withdrawal> capellaWithdrawals =
        getExpectedWithdrawals(
            preState,
            schemaDefinitionsCapella,
            miscHelpers,
            specConfigCapella,
            predicates,
            new ArrayList<>());
    return new ExpectedWithdrawals(capellaWithdrawals, 0, schemaDefinitionsCapella);
  }

  private static ExpectedWithdrawals createFromElectraState(
      final BeaconStateElectra preState,
      final SchemaDefinitionsElectra schemaDefinitions,
      final MiscHelpersElectra miscHelpers,
      final SpecConfigElectra specConfig,
      final PredicatesElectra predicates) {
    final WithdrawalSummary withdrawalSummary =
        getPendingPartialWithdrawals(preState, schemaDefinitions, miscHelpers, specConfig);
    final List<Withdrawal> withdrawals =
        getExpectedWithdrawals(
            preState,
            schemaDefinitions,
            miscHelpers,
            specConfig,
            predicates,
            withdrawalSummary.withdrawalList());
    return new ExpectedWithdrawals(
        withdrawals, withdrawalSummary.partialWithdrawalCount(), schemaDefinitions);
  }

  public List<Withdrawal> getWithdrawalList() {
    return withdrawalList;
  }

  public int getPartialWithdrawalCount() {
    return partialWithdrawalCount;
  }

  static WithdrawalSummary getPendingPartialWithdrawals(
      final BeaconStateElectra preState,
      final SchemaDefinitionsElectra schemaDefinitions,
      final MiscHelpersElectra miscHelpers,
      final SpecConfigElectra specConfig) {
    final UInt64 epoch = miscHelpers.computeEpochAtSlot(preState.getSlot());
    final int maxPendingPartialWithdrawals = specConfig.getMaxPendingPartialsPerWithdrawalsSweep();
    final List<Withdrawal> withdrawals = new ArrayList<>();
    int processedPartialWithdrawalsCount = 0;
    UInt64 withdrawalIndex = preState.getNextWithdrawalIndex();

    for (PendingPartialWithdrawal withdrawal : preState.getPendingPartialWithdrawals()) {
      if (withdrawal.getWithdrawableEpoch().isGreaterThan(epoch)
          || withdrawals.size() == maxPendingPartialWithdrawals) {
        break;
      }
      final int validatorIndex = withdrawal.getValidatorIndex();
      final Validator validator = preState.getValidators().get(validatorIndex);
      final UInt64 partiallyWithdrawnBalance =
          getPartiallyWithdrawnBalance(withdrawals, UInt64.valueOf(validatorIndex));
      final UInt64 remainingBalance =
          preState
              .getBalances()
              .get(withdrawal.getValidatorIndex())
              .get()
              .minusMinZero(partiallyWithdrawnBalance);
      final boolean hasSufficientEffectiveBalance =
          validator
              .getEffectiveBalance()
              .isGreaterThanOrEqualTo(specConfig.getMinActivationBalance());
      final boolean hasExcessBalance =
          remainingBalance.isGreaterThan(specConfig.getMinActivationBalance());
      LOG.trace(
          "pending withdrawal validator index {}, remaining balance {}, requested amount {}; exitEpoch {}, hasSufficientEffectiveBalance {}, hasExcessBalance {}",
          withdrawal.getValidatorIndex(),
          remainingBalance,
          withdrawal.getAmount(),
          validator.getExitEpoch(),
          hasSufficientEffectiveBalance,
          hasExcessBalance);

      if (validator.getExitEpoch().equals(FAR_FUTURE_EPOCH)
          && hasSufficientEffectiveBalance
          && hasExcessBalance) {
        final UInt64 withdrawableBalance =
            withdrawal
                .getAmount()
                .min(remainingBalance.minusMinZero(specConfig.getMinActivationBalance()));
        withdrawals.add(
            schemaDefinitions
                .getWithdrawalSchema()
                .create(
                    withdrawalIndex,
                    UInt64.valueOf(withdrawal.getValidatorIndex()),
                    getEthAddressFromWithdrawalCredentials(validator),
                    withdrawableBalance));
        withdrawalIndex = withdrawalIndex.increment();
      }
      processedPartialWithdrawalsCount++;
    }
    return new WithdrawalSummary(withdrawals, processedPartialWithdrawalsCount);
  }

  static Bytes20 getEthAddressFromWithdrawalCredentials(final Validator validator) {
    return new Bytes20(validator.getWithdrawalCredentials().slice(12));
  }

  // get_expected_withdrawals
  private static List<Withdrawal> getExpectedWithdrawals(
      final BeaconStateCapella preState,
      final SchemaDefinitionsCapella schemaDefinitionsCapella,
      final MiscHelpers miscHelpers,
      final SpecConfigCapella specConfigCapella,
      final Predicates predicates,
      final List<Withdrawal> partialWithdrawals) {
    final WithdrawalSchema withdrawalSchema = schemaDefinitionsCapella.getWithdrawalSchema();
    final UInt64 epoch = miscHelpers.computeEpochAtSlot(preState.getSlot());
    final SszList<Validator> validators = preState.getValidators();
    final SszUInt64List balances = preState.getBalances();
    final int validatorCount = validators.size();
    final int maxWithdrawalsPerPayload = specConfigCapella.getMaxWithdrawalsPerPayload();
    final int maxValidatorsPerWithdrawalsSweep =
        specConfigCapella.getMaxValidatorsPerWithdrawalSweep();
    final int bound = Math.min(validatorCount, maxValidatorsPerWithdrawalsSweep);

    UInt64 withdrawalIndex =
        partialWithdrawals.isEmpty()
            ? preState.getNextWithdrawalIndex()
            : nextWithdrawalAfter(partialWithdrawals);
    int validatorIndex = preState.getNextWithdrawalValidatorIndex().intValue();

    for (int i = 0; i < bound; i++) {
      final Validator validator = validators.get(validatorIndex);
      if (predicates.hasExecutionWithdrawalCredential(validator)) {
        final UInt64 partiallyWithdrawnBalance =
            getPartiallyWithdrawnBalance(partialWithdrawals, UInt64.valueOf(validatorIndex));
        final UInt64 balance =
            balances.get(validatorIndex).get().minusMinZero(partiallyWithdrawnBalance);

        if (predicates.isFullyWithdrawableValidatorCredentialsChecked(validator, balance, epoch)) {
          partialWithdrawals.add(
              withdrawalSchema.create(
                  withdrawalIndex,
                  UInt64.valueOf(validatorIndex),
                  getEthAddressFromWithdrawalCredentials(validator),
                  balance));
          withdrawalIndex = withdrawalIndex.increment();
        } else if (predicates.isPartiallyWithdrawableValidatorEth1CredentialsChecked(
            validator, balance)) {
          partialWithdrawals.add(
              withdrawalSchema.create(
                  withdrawalIndex,
                  UInt64.valueOf(validatorIndex),
                  getEthAddressFromWithdrawalCredentials(validator),
                  balance.minusMinZero(miscHelpers.getMaxEffectiveBalance(validator))));
          withdrawalIndex = withdrawalIndex.increment();
        }

        if (partialWithdrawals.size() == maxWithdrawalsPerPayload) {
          break;
        }
      }

      validatorIndex = (validatorIndex + 1) % validatorCount;
    }

    return partialWithdrawals;
  }

  static UInt64 getPartiallyWithdrawnBalance(
      final List<Withdrawal> currentWithdrawals, final UInt64 validatorIndex) {
    return currentWithdrawals.stream()
        .filter(partialWithdrawal -> partialWithdrawal.getValidatorIndex().equals(validatorIndex))
        .map(Withdrawal::getAmount)
        .reduce(UInt64.ZERO, UInt64::plus);
  }

  public void processWithdrawals(
      final MutableBeaconState genericState,
      final ExecutionPayloadSummary payloadSummary,
      final SchemaDefinitionsCapella schemaDefinitionsCapella,
      final BeaconStateMutators beaconStateMutators,
      final SpecConfigCapella specConfigCapella)
      throws BlockProcessingException {
    final SszList<Withdrawal> expectedWithdrawals =
        getExpectedWithdrawalsSszList(schemaDefinitionsCapella);

    assertWithdrawalsInExecutionPayloadMatchExpected(payloadSummary, expectedWithdrawals);

    processWithdrawalsUnchecked(
        genericState, schemaDefinitionsCapella, beaconStateMutators, specConfigCapella);
  }

  void processWithdrawalsUnchecked(
      final MutableBeaconState genericState,
      final SchemaDefinitionsCapella schemaDefinitionsCapella,
      final BeaconStateMutators beaconStateMutators,
      final SpecConfigCapella specConfigCapella) {
    final MutableBeaconStateCapella state = MutableBeaconStateCapella.required(genericState);
    final SszList<Withdrawal> expectedWithdrawals =
        getExpectedWithdrawalsSszList(schemaDefinitionsCapella);

    for (int i = 0; i < expectedWithdrawals.size(); i++) {
      final Withdrawal withdrawal = expectedWithdrawals.get(i);
      beaconStateMutators.decreaseBalance(
          state, withdrawal.getValidatorIndex().intValue(), withdrawal.getAmount());
    }

    if (partialWithdrawalCount > 0) {
      reducePendingWithdrawals(MutableBeaconStateElectra.required(state));
    }

    final int validatorCount = genericState.getValidators().size();
    final int maxWithdrawalsPerPayload = specConfigCapella.getMaxWithdrawalsPerPayload();
    final int maxValidatorsPerWithdrawalsSweep =
        specConfigCapella.getMaxValidatorsPerWithdrawalSweep();
    if (!expectedWithdrawals.isEmpty()) {
      final Withdrawal latestWithdrawal = expectedWithdrawals.get(expectedWithdrawals.size() - 1);
      state.setNextWithdrawalIndex(latestWithdrawal.getIndex().increment());
    }

    if (expectedWithdrawals.size() == maxWithdrawalsPerPayload) {
      // Update the next validator index to start the next withdrawal sweep
      final Withdrawal latestWithdrawal = expectedWithdrawals.get(expectedWithdrawals.size() - 1);
      final int nextWithdrawalValidatorIndex = latestWithdrawal.getValidatorIndex().intValue() + 1;
      state.setNextWithdrawalValidatorIndex(
          UInt64.valueOf(nextWithdrawalValidatorIndex % validatorCount));
    } else {
      // Advance sweep by the max length of the sweep if there was not a full set of withdrawals
      final int nextWithdrawalValidatorIndex =
          state.getNextWithdrawalValidatorIndex().intValue() + maxValidatorsPerWithdrawalsSweep;
      state.setNextWithdrawalValidatorIndex(
          UInt64.valueOf(nextWithdrawalValidatorIndex % validatorCount));
    }
  }

  private SszList<Withdrawal> getExpectedWithdrawalsSszList(
      final SchemaDefinitionsCapella schemaDefinitions) {
    if (maybeWithdrawalsSszList.isEmpty()) {
      maybeWithdrawalsSszList =
          Optional.of(
              schemaDefinitions
                  .getExecutionPayloadSchema()
                  .getWithdrawalsSchemaRequired()
                  .createFromElements(withdrawalList));
    }
    return maybeWithdrawalsSszList.get();
  }

  private void reducePendingWithdrawals(final MutableBeaconStateElectra state) {
    final SszListSchema<PendingPartialWithdrawal, ?> schema =
        state.getPendingPartialWithdrawals().getSchema();
    if (state.getPendingPartialWithdrawals().size() == partialWithdrawalCount) {
      state.setPendingPartialWithdrawals(schema.createFromElements(List.of()));
    } else {
      final List<PendingPartialWithdrawal> pendingPartialWithdrawals =
          state.getPendingPartialWithdrawals().asList();
      state.setPendingPartialWithdrawals(
          schema.createFromElements(
              pendingPartialWithdrawals.subList(
                  partialWithdrawalCount, pendingPartialWithdrawals.size())));
    }
  }

  private static void assertWithdrawalsInExecutionPayloadMatchExpected(
      final ExecutionPayloadSummary payloadSummary, final SszList<Withdrawal> expectedWithdrawals)
      throws BlockProcessingException {
    // the spec does an element-to-element comparison but Teku is comparing the hash of the tree
    if (payloadSummary.getOptionalWithdrawalsRoot().isEmpty()
        || !expectedWithdrawals
            .hashTreeRoot()
            .equals(payloadSummary.getOptionalWithdrawalsRoot().get())) {
      final String msg =
          String.format(
              "Withdrawals in execution payload are different from expected (expected withdrawals root is %s but was "
                  + "%s)",
              expectedWithdrawals.hashTreeRoot(),
              payloadSummary
                  .getOptionalWithdrawalsRoot()
                  .map(Bytes::toHexString)
                  .orElse("MISSING"));
      throw new BlockProcessingException(msg);
    }
  }

  private static UInt64 nextWithdrawalAfter(final List<Withdrawal> partialWithdrawals) {
    return partialWithdrawals.getLast().getIndex().increment();
  }

  public record WithdrawalSummary(List<Withdrawal> withdrawalList, int partialWithdrawalCount) {}
}
