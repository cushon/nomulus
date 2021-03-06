// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.domain;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.flows.FlowTestCase.UserPrivileges.SUPERUSER;
import static google.registry.model.billing.BillingEvent.Flag.ANCHOR_TENANT;
import static google.registry.model.domain.fee.Fee.FEE_EXTENSION_URIS;
import static google.registry.model.eppcommon.StatusValue.OK;
import static google.registry.model.eppcommon.StatusValue.SERVER_HOLD;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.assertPollMessagesForResource;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.deleteTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveDomainApplication;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainResourceSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.tmch.LordnTask.QUEUE_CLAIMS;
import static google.registry.tmch.LordnTask.QUEUE_SUNRISE;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.DomainCreateFlow.AnchorTenantCreatePeriodException;
import google.registry.flows.domain.DomainCreateFlow.DomainHasOpenApplicationsException;
import google.registry.flows.domain.DomainCreateFlow.MustHaveSignedMarksInCurrentPhaseException;
import google.registry.flows.domain.DomainCreateFlow.NoGeneralRegistrationsInCurrentPhaseException;
import google.registry.flows.domain.DomainCreateFlow.SignedMarksOnlyDuringSunriseException;
import google.registry.flows.domain.DomainFlowTmchUtils.FoundMarkExpiredException;
import google.registry.flows.domain.DomainFlowTmchUtils.FoundMarkNotYetValidException;
import google.registry.flows.domain.DomainFlowTmchUtils.NoMarksFoundMatchingDomainException;
import google.registry.flows.domain.DomainFlowUtils.AcceptedTooLongAgoException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.ClaimsPeriodEndedException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.DomainNameExistsAsTldException;
import google.registry.flows.domain.DomainFlowUtils.DomainNotAllowedForTldWithCreateRestrictionException;
import google.registry.flows.domain.DomainFlowUtils.DomainReservedException;
import google.registry.flows.domain.DomainFlowUtils.DuplicateContactForRoleException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.ExceedsMaxRegistrationYearsException;
import google.registry.flows.domain.DomainFlowUtils.ExpiredClaimException;
import google.registry.flows.domain.DomainFlowUtils.FeeDescriptionMultipleMatchesException;
import google.registry.flows.domain.DomainFlowUtils.FeeDescriptionParseException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredDuringEarlyAccessProgramException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.InvalidTcnIdChecksumException;
import google.registry.flows.domain.DomainFlowUtils.InvalidTrademarkValidatorException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourcesDoNotExistException;
import google.registry.flows.domain.DomainFlowUtils.MalformedTcnIdException;
import google.registry.flows.domain.DomainFlowUtils.MaxSigLifeNotSupportedException;
import google.registry.flows.domain.DomainFlowUtils.MissingAdminContactException;
import google.registry.flows.domain.DomainFlowUtils.MissingClaimsNoticeException;
import google.registry.flows.domain.DomainFlowUtils.MissingContactTypeException;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.MissingTechnicalContactException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedForDomainException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedForTldException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForNameserverRestrictedDomainException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverWhitelistException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveToCreateDomainsException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TooManyDsRecordsException;
import google.registry.flows.domain.DomainFlowUtils.TooManyNameserversException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.UnexpectedClaimsNoticeException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedMarkTypeException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.InvalidAllocationTokenException;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.ResourceAlreadyExistsException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.Registry.TldType;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.util.Map;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainCreateFlow}. */
public class DomainCreateFlowTest extends ResourceFlowTestCase<DomainCreateFlow, DomainResource> {

  private static final String CLAIMS_KEY = "2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001";

  public DomainCreateFlowTest() {
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z").minus(Duration.millis(1)));
  }

  @Before
  public void initCreateTest() {
    createTld("tld");
    persistResource(
        new AllocationToken.Builder().setToken("abcDEF23456").setDomainName("anchor.tld").build());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "tld-reserved",
                    "reserved,FULLY_BLOCKED",
                    "resdom,RESERVED_FOR_SPECIFIC_USE",
                    "anchor,RESERVED_FOR_ANCHOR_TENANT",
                    "test-and-validate,NAME_COLLISION",
                    "badcrash,NAME_COLLISION"),
                persistReservedList("global-list", "resdom,FULLY_BLOCKED"))
            .build());
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
  }

  /**
   * Create host and contact entries for testing.
   *
   * @param hostTld the TLD of the host (which might be an external TLD)
   */
  private void persistContactsAndHosts(String hostTld) {
    for (int i = 1; i <= 14; ++i) {
      persistActiveHost(String.format("ns%d.example.%s", i, hostTld));
    }
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    clock.advanceOneMilli();
  }

  private void persistContactsAndHosts() {
    persistContactsAndHosts("net"); // domain_create.xml uses hosts on "net".
  }

  private void assertSuccessfulCreate(
      String domainTld, ImmutableSet<BillingEvent.Flag> expectedBillingFlags) throws Exception {
    DomainResource domain = reloadResourceByForeignKey();

    // Calculate the total cost.
    Money cost =
        isDomainPremium(getUniqueIdFromCommand(), clock.nowUtc())
            ? Money.of(USD, 200)
            : Money.of(USD, 26);
    Money eapFee =
        Money.of(
            Registry.get(domainTld).getCurrency(),
            Registry.get(domainTld).getEapFeeFor(clock.nowUtc()).getCost());
    boolean isAnchorTenant = expectedBillingFlags.contains(ANCHOR_TENANT);
    DateTime billingTime =
        isAnchorTenant
            ? clock.nowUtc().plus(Registry.get(domainTld).getAnchorTenantAddGracePeriodLength())
            : clock.nowUtc().plus(Registry.get(domainTld).getAddGracePeriodLength());
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertAboutDomains()
        .that(domain)
        .hasRegistrationExpirationTime(
            ofy().load().key(domain.getAutorenewBillingEvent()).now().getEventTime())
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_CREATE)
        .and()
        .hasPeriodYears(2);
    // There should be one bill for the create and one for the recurring autorenew event.
    BillingEvent.OneTime createBillingEvent =
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.CREATE)
            .setTargetId(getUniqueIdFromCommand())
            .setClientId("TheRegistrar")
            .setCost(cost)
            .setPeriodYears(2)
            .setEventTime(clock.nowUtc())
            .setBillingTime(billingTime)
            .setFlags(expectedBillingFlags)
            .setParent(historyEntry)
            .build();

    BillingEvent.Recurring renewBillingEvent =
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntry)
            .build();

    ImmutableSet.Builder<BillingEvent> expectedBillingEvents =
        new ImmutableSet.Builder<BillingEvent>().add(createBillingEvent).add(renewBillingEvent);

    // If EAP is applied, a billing event for EAP should be present.
    if (!eapFee.isZero()) {
      BillingEvent.OneTime eapBillingEvent =
          new BillingEvent.OneTime.Builder()
              .setReason(Reason.FEE_EARLY_ACCESS)
              .setTargetId(getUniqueIdFromCommand())
              .setClientId("TheRegistrar")
              .setPeriodYears(1)
              .setCost(eapFee)
              .setEventTime(clock.nowUtc())
              .setBillingTime(billingTime)
              .setFlags(expectedBillingFlags)
              .setParent(historyEntry)
              .build();
      expectedBillingEvents.add(eapBillingEvent);
    }
    assertBillingEvents(expectedBillingEvents.build());
    assertPollMessagesForResource(
        domain,
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getFullyQualifiedDomainName())
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntry)
            .build());

    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(GracePeriodStatus.ADD, billingTime, "TheRegistrar", null),
            createBillingEvent));
    assertDnsTasksEnqueued(getUniqueIdFromCommand());
    assertEppResourceIndexEntityFor(domain);
  }

  private void assertNoLordn() throws Exception {
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasSmdId(null)
        .and()
        .hasLaunchNotice(null);
    assertNoTasksEnqueued(QUEUE_CLAIMS, QUEUE_SUNRISE);
  }

  private void assertSunriseLordn(String domainName) throws Exception {
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasSmdId("0000001761376042759136-65535")
        .and()
        .hasLaunchNotice(null);
    String expectedPayload =
        String.format(
            "%s,%s,0000001761376042759136-65535,1,2014-09-09T09:09:09.001Z",
            reloadResourceByForeignKey().getRepoId(), domainName);
    assertTasksEnqueued(QUEUE_SUNRISE, new TaskMatcher().payload(expectedPayload));
  }

  private void assertClaimsLordn() throws Exception {
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasSmdId(null)
        .and()
        .hasLaunchNotice(
            LaunchNotice.create(
                "370d0b7c9223372036854775807",
                "tmch",
                DateTime.parse("2010-08-16T09:00:00.0Z"),
                DateTime.parse("2009-08-16T09:00:00.0Z")));
    TaskMatcher task =
        new TaskMatcher()
            .payload(
                reloadResourceByForeignKey().getRepoId()
                    + ",example-one.tld,370d0b7c9223372036854775807,1,"
                    + "2009-08-16T09:00:00.001Z,2009-08-16T09:00:00.000Z");
    assertTasksEnqueued(QUEUE_CLAIMS, task);
  }

  private void doSuccessfulTest(
      String domainTld,
      String responseXmlFile,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions)
      throws Exception {
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE, userPrivileges, loadFile(responseXmlFile, substitutions));
    assertSuccessfulCreate(domainTld, ImmutableSet.of());
    assertNoLordn();
  }

  private void doSuccessfulTest(
      String domainTld, String responseXmlFile, Map<String, String> substitutions)
      throws Exception {
    doSuccessfulTest(domainTld, responseXmlFile, UserPrivileges.NORMAL, substitutions);
  }

  private void doSuccessfulTest(String domainTld) throws Exception {
    doSuccessfulTest(
        domainTld, "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  private void doSuccessfulTest() throws Exception {
    doSuccessfulTest("tld");
  }

  @Test
  public void testDryRun() throws Exception {
    persistContactsAndHosts();
    dryRunFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
  }

  @Test
  public void testSuccess_neverExisted() throws Exception {
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_cachingDisabled() throws Exception {
    RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(false);
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_create_no_cltrid.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_no_cltrid.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testFailure_invalidAllocationToken() {
    setEppInput("domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_alreadyRedemeedAllocationToken() {
    setEppInput("domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setRedemptionHistoryEntry(Key.create(HistoryEntry.class, 505L))
            .build());
    clock.advanceOneMilli();
    EppException thrown =
        assertThrows(AlreadyRedeemedAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_validAllocationToken_isRedeemed() throws Exception {
    setEppInput("domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistContactsAndHosts();
    AllocationToken token =
        persistResource(new AllocationToken.Builder().setToken("abc123").build());
    clock.advanceOneMilli();
    doSuccessfulTest();
    HistoryEntry historyEntry =
        ofy().load().type(HistoryEntry.class).ancestor(reloadResourceByForeignKey()).first().now();
    assertThat(ofy().load().entity(token).now().getRedemptionHistoryEntry())
        .isEqualTo(Key.create(historyEntry));
  }

  @Test
  public void testSuccess_multipartTld() throws Exception {
    createTld("foo.tld");
    setEppInput("domain_create_with_tld.xml", ImmutableMap.of("TLD", "foo.tld"));
    persistContactsAndHosts("foo.tld");
    assertTransactionalFlow(true);
    String expectedResponseXml =
        loadFile(
            "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.foo.tld"));
    runFlowAssertResponse(CommitMode.LIVE, UserPrivileges.NORMAL, expectedResponseXml);
    assertSuccessfulCreate("foo.tld", ImmutableSet.of());
    assertNoLordn();
  }

  @Test
  public void testFailure_domainNameExistsAsTld_lowercase() {
    createTlds("foo.tld", "tld");
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "foo.tld"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(DomainNameExistsAsTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_domainNameExistsAsTld_uppercase() {
    createTlds("foo.tld", "tld");
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "FOO.TLD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(BadDomainNameCharacterException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_anchorTenantViaExtension() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_create_anchor_tenant.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  public void testFailure_generalAvailability_withEncodedSignedMark() {
    createTld("tld", GENERAL_AVAILABILITY);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "open"));
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(SignedMarksOnlyDuringSunriseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_generalAvailability_superuserMismatchedEncodedSignedMark() {
    createTld("tld", GENERAL_AVAILABILITY);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "wrong.tld", "PHASE", "open"));
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoMarksFoundMatchingDomainException.class, this::runFlowAsSuperuser);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  public void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  public void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  public void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  public void testFailure_refundableFee_v06() {
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_refundableFee_v11() {
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_refundableFee_v12() {
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_gracePeriodFee_v06() {
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_gracePeriodFee_v11() {
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_gracePeriodFee_v12() {
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_appliedFee_v06() {
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_appliedFee_v11() {
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_appliedFee_v12() {
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_metadata() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_create_metadata.xml");
    persistContactsAndHosts();
    doSuccessfulTest();
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_CREATE)
        .and()
        .hasMetadataReason("domain-create-test")
        .and()
        .hasMetadataRequestedByRegistrar(false);
  }

  @Test
  public void testFailure_metadataNotFromTool() {
    setEppInput("domain_create_metadata.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(OnlyToolCanPassMetadataException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_premium() throws Exception {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    doSuccessfulTest(
        "example", "domain_create_response_premium.xml", ImmutableMap.of("DOMAIN", "rich.example"));
  }

  @Test
  public void testSuccess_premiumAndEap() throws Exception {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(true).build());
    setEppInput("domain_create_premium_eap.xml");
    persistContactsAndHosts("net");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "example",
        "domain_create_response_premium_eap.xml",
        ImmutableMap.of("DOMAIN", "rich.example"));
  }

  /**
   * Test fix for a bug where we were looking at the length of the unicode string but indexing into
   * the punycode string. In rare cases (3 and 4 letter labels) this would cause us to think there
   * was a trailing dash in the domain name and fail to create it.
   */
  @Test
  public void testSuccess_unicodeLengthBug() throws Exception {
    createTld("xn--q9jyb4c");
    persistContactsAndHosts("net");
    eppLoader.replaceAll("example.tld", "osx.xn--q9jyb4c");
    runFlow();
  }

  @Test
  public void testSuccess_nonDefaultAddGracePeriod() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAddGracePeriodLength(Duration.standardMinutes(6))
            .build());
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_existedButWasDeleted() throws Exception {
    persistContactsAndHosts();
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_maxNumberOfNameservers() throws Exception {
    setEppInput("domain_create_13_nameservers.xml");
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_secDns() throws Exception {
    setEppInput("domain_create_dsdata_no_maxsiglife.xml");
    persistContactsAndHosts("tld"); // For some reason this sample uses "tld".
    doSuccessfulTest("tld");
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasExactlyDsData(
            DelegationSignerData.create(12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC")));
  }

  @Test
  public void testSuccess_secDnsMaxRecords() throws Exception {
    setEppInput("domain_create_dsdata_8_records.xml");
    persistContactsAndHosts("tld"); // For some reason this sample uses "tld".
    doSuccessfulTest("tld");
    assertAboutDomains().that(reloadResourceByForeignKey()).hasNumDsData(8);
  }

  @Test
  public void testSuccess_idn() throws Exception {
    createTld("xn--q9jyb4c");
    setEppInput("domain_create_idn_minna.xml");
    persistContactsAndHosts("net");
    runFlowAssertResponse(loadFile("domain_create_response_idn_minna.xml"));
    assertSuccessfulCreate("xn--q9jyb4c", ImmutableSet.of());
    assertDnsTasksEnqueued("xn--abc-873b2e7eb1k8a4lpjvv.xn--q9jyb4c");
  }

  @Test
  public void testSuccess_noNameserversOrDsData() throws Exception {
    setEppInput("domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertNoDnsTasksEnqueued();
  }

  @Test
  public void testSuccess_periodNotSpecified() throws Exception {
    setEppInput("domain_create_missing_period.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")),
        "epp.response.resData.creData.exDate"); // Ignore expiration date; we verify it below
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasRegistrationExpirationTime(clock.nowUtc().plusYears(1));
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testFailure_periodInMonths() {
    setEppInput("domain_create_months.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_claimsNotice() throws Exception {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of());
    assertDnsTasksEnqueued("example-one.tld");
    assertClaimsLordn();
  }

  @Test
  public void testSuccess_noClaimsNotice_forClaimsListName_afterClaimsPeriodEnd() throws Exception {
    persistClaimsList(ImmutableMap.of("example", CLAIMS_KEY));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder().setClaimsPeriodEnd(clock.nowUtc()).build());
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of());
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testFailure_missingClaimsNotice() {
    persistClaimsList(ImmutableMap.of("example", CLAIMS_KEY));
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingClaimsNoticeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertNoTasksEnqueued(QUEUE_CLAIMS, QUEUE_SUNRISE);
  }

  @Test
  public void testFailure_claimsNoticeProvided_nameNotOnClaimsList() {
    setEppInput("domain_create_claim_notice.xml");
    persistClaimsList(ImmutableMap.of());
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnexpectedClaimsNoticeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_claimsNoticeProvided_claimsPeriodEnded() {
    setEppInput("domain_create_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder().setClaimsPeriodEnd(clock.nowUtc()).build());
    EppException thrown = assertThrows(ClaimsPeriodEndedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_tooManyNameservers() {
    setEppInput("domain_create_14_nameservers.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(TooManyNameserversException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_secDnsMaxSigLife() {
    setEppInput("domain_create_dsdata.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MaxSigLifeNotSupportedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_secDnsTooManyDsRecords() {
    setEppInput("domain_create_dsdata_9_records.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(TooManyDsRecordsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongExtension() {
    setEppInput("domain_create_wrong_extension.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnimplementedExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongFeeAmount_v06() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistResource(
        Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongFeeAmount_v11() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistResource(
        Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongFeeAmount_v12() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistResource(
        Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongCurrency_v06() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(CurrencyUnit.EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongCurrency_v11() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(CurrencyUnit.EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongCurrency_v12() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(CurrencyUnit.EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_alreadyExists() throws Exception {
    persistContactsAndHosts();
    persistActiveDomain(getUniqueIdFromCommand());
    ResourceAlreadyExistsException thrown =
        assertThrows(ResourceAlreadyExistsException.class, this::runFlow);
    assertAboutEppExceptions()
        .that(thrown)
        .marshalsToXml()
        .and()
        .hasMessage(
            String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
  }

  @Test
  public void testFailure_reserved() {
    setEppInput("domain_create_reserved.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(DomainReservedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_anchorTenant_viaAuthCode_wrongAuthCode() {
    setEppInput("domain_create_anchor_wrong_authcode.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(DomainReservedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_anchorTenant_notTwoYearPeriod() {
    setEppInput("domain_create_anchor_authcode_invalid_years.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(AnchorTenantCreatePeriodException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_anchorTenant_viaAuthCode() throws Exception {
    setEppInput("domain_create_anchor_authcode.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  public void testSuccess_anchorTenant_viaAllocationTokenExtension() throws Exception {
    setEppInput("domain_create_anchor_allocationtoken.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
    AllocationToken reloadedToken =
        ofy().load().key(Key.create(AllocationToken.class, "abcDEF23456")).now();
    assertThat(reloadedToken.isRedeemed()).isTrue();
    assertThat(reloadedToken.getRedemptionHistoryEntry())
        .isEqualTo(Key.create(getHistoryEntries(reloadResourceByForeignKey()).get(0)));
  }

  @Test
  public void testSuccess_anchorTenant_viaAuthCode_withClaims() throws Exception {
    persistResource(
        new AllocationToken.Builder().setDomainName("example-one.tld").setToken("2fooBAR").build());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "anchor-with-claims", "example-one,RESERVED_FOR_ANCHOR_TENANT"))
            .build());
    setEppInput("domain_create_claim_notice.xml");
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertDnsTasksEnqueued("example-one.tld");
    assertClaimsLordn();
  }

  @Test
  public void testSuccess_reservedDomain_viaAllocationTokenExtension() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder().setToken("abc123").setDomainName("resdom.tld").build());
    // Despite the domain being FULLY_BLOCKED, the non-superuser create succeeds the domain is also
    // RESERVED_FOR_SPECIFIC_USE and the correct allocation token is passed.
    setEppInput("domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "resdom.tld"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "resdom.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of());
    assertNoLordn();
    AllocationToken reloadedToken = ofy().load().entity(token).now();
    assertThat(reloadedToken.isRedeemed()).isTrue();
    assertThat(reloadedToken.getRedemptionHistoryEntry())
        .isEqualTo(Key.create(getHistoryEntries(reloadResourceByForeignKey()).get(0)));
  }

  @Test
  public void testSuccess_superuserReserved() throws Exception {
    setEppInput("domain_create_reserved.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE, SUPERUSER, loadFile("domain_create_reserved_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of());
  }

  @Test
  public void testSuccess_reservedNameCollisionDomain_inSunrise_setsServerHoldAndPollMessage()
      throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, START_DATE_SUNRISE))
            .build());
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-and-validate.tld", "PHASE", "sunrise"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of("DOMAIN", "test-and-validate.tld")));

    assertSunriseLordn("test-and-validate.tld");

    // Check for SERVER_HOLD status, no DNS tasks enqueued, and collision poll message.
    assertNoDnsTasksEnqueued();
    DomainResource domain = reloadResourceByForeignKey();
    assertThat(domain.getStatusValues()).contains(SERVER_HOLD);
    assertPollMessagesWithCollisionOneTime(domain);
  }

  @Test
  public void testSuccess_reservedNameCollisionDomain_withSuperuser_setsServerHoldAndPollMessage()
      throws Exception {
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "badcrash.tld"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE,
        SUPERUSER,
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "badcrash.tld")));

    // Check for SERVER_HOLD status, no DNS tasks enqueued, and collision poll message.
    assertNoDnsTasksEnqueued();
    DomainResource domain = reloadResourceByForeignKey();
    assertThat(domain.getStatusValues()).contains(SERVER_HOLD);
    assertPollMessagesWithCollisionOneTime(domain);
  }

  private void assertPollMessagesWithCollisionOneTime(DomainResource domain) {
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertPollMessagesForResource(
        domain,
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getFullyQualifiedDomainName())
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntry)
            .build(),
        new PollMessage.OneTime.Builder()
            .setParent(historyEntry)
            .setEventTime(domain.getCreationTime())
            .setClientId("TheRegistrar")
            .setMsg(DomainFlowUtils.COLLISION_MESSAGE)
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        domain.getFullyQualifiedDomainName(),
                        true,
                        historyEntry.getTrid(),
                        clock.nowUtc())))
            .setId(1L)
            .build());
  }

  @Test
  public void testFailure_missingHost() {
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns2.example.net)");
  }

  @Test
  public void testFailure_pendingDeleteHost() {
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    persistResource(
        newHostResource("ns2.example.net")
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.net");
  }

  @Test
  public void testFailure_openApplication() throws Exception {
    persistContactsAndHosts();
    persistActiveDomainApplication(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    EppException thrown = assertThrows(DomainHasOpenApplicationsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserOpenApplication() throws Exception {
    persistContactsAndHosts();
    persistActiveDomainApplication(getUniqueIdFromCommand());
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_rejectedApplication() throws Exception {
    persistContactsAndHosts();
    persistResource(
        newDomainApplication(getUniqueIdFromCommand())
            .asBuilder()
            .setApplicationStatus(ApplicationStatus.REJECTED)
            .build());
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @Test
  public void testFailure_missingContact() {
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(sh8013)");
  }

  @Test
  public void testFailure_pendingDeleteContact() {
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("sh8013");
    persistResource(
        newContactResource("jd1234")
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("jd1234");
  }

  @Test
  public void testFailure_wrongTld() {
    persistContactsAndHosts("net");
    deleteTld("tld");
    EppException thrown = assertThrows(TldDoesNotExistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_predelegation() {
    createTld("tld", TldState.PREDELEGATION);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_sunrise() {
    createTld("tld", TldState.SUNRISE);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_sunrush() {
    createTld("tld", TldState.SUNRUSH);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_landrush() {
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_startDateSunrise_missingLaunchExtension() {
    createTld("tld", START_DATE_SUNRISE);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_quietPeriod() {
    createTld("tld", TldState.QUIET_PERIOD);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserPredelegation() throws Exception {
    createTld("tld", TldState.PREDELEGATION);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_superuserSunrush() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_superuserStartDateSunrise_isSuperuser() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_superuserQuietPeriod() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_superuserOverridesPremiumNameBlock() throws Exception {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    runFlowAssertResponse(
        CommitMode.LIVE, SUPERUSER, loadFile("domain_create_response_premium.xml"));
    assertSuccessfulCreate("example", ImmutableSet.of());
  }

  @Test
  public void testSuccess_customLogicIsCalled_andSavesExtraEntity() throws Exception {
    // @see TestDomainCreateFlowCustomLogic for what the label "custom-logic-test" triggers.
    ImmutableMap<String, String> substitutions = ImmutableMap.of("DOMAIN", "custom-logic-test.tld");
    setEppInput("domain_create.xml", substitutions);
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.NORMAL,
        loadFile("domain_create_response.xml", substitutions));
    DomainResource domain = reloadResourceByForeignKey();
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertPollMessagesForResource(
        domain,
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getFullyQualifiedDomainName())
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntry)
            .build(),
        new PollMessage.OneTime.Builder()
            .setParent(historyEntry)
            .setEventTime(domain.getCreationTime())
            .setClientId("TheRegistrar")
            .setMsg("Custom logic was triggered")
            .setId(1L)
            .build());
  }

  @Test
  public void testFailure_duplicateContact() {
    setEppInput("domain_create_duplicate_contact.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_missingContactType() {
    // We need to test for missing type, but not for invalid - the schema enforces that for us.
    setEppInput("domain_create_missing_contact_type.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingContactTypeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_missingRegistrant() {
    setEppInput("domain_create_missing_registrant.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingRegistrantException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_missingAdmin() {
    setEppInput("domain_create_missing_admin.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingAdminContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_missingTech() {
    setEppInput("domain_create_missing_tech.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingTechnicalContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_missingNonRegistrantContacts() {
    setEppInput("domain_create_missing_non_registrant_contacts.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingAdminContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_badIdn() {
    createTld("xn--q9jyb4c");
    setEppInput("domain_create_bad_idn_minna.xml");
    persistContactsAndHosts("net");
    EppException thrown = assertThrows(InvalidIdnDomainLabelException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_badValidatorId() {
    setEppInput("domain_create_bad_validator_id.xml");
    persistClaimsList(ImmutableMap.of("exampleone", CLAIMS_KEY));
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidTrademarkValidatorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_codeMark() {
    setEppInput("domain_create_code_with_mark.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedMarkTypeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /**
   * Tests that a sunrise registration fails with the correct error during end-date sunrise.
   *
   * <p>Makes sure that a sunrise registration without signed mark during end-date sunrise fails
   * with the "wrong phase" error rather than the "missing signed mark" error.
   */
  @Test
  public void testFailure_registrationDuringEndDateSunrise_wrongPhase() {
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_expiredClaim() {
    clock.setTo(DateTime.parse("2010-08-17T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(ExpiredClaimException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_expiredAcceptance() {
    clock.setTo(DateTime.parse("2009-09-16T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(AcceptedTooLongAgoException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_malformedTcnIdWrongLength() {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_malformed_claim_notice1.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MalformedTcnIdException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_malformedTcnIdBadChar() {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_malformed_claim_notice2.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MalformedTcnIdException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_badTcnIdChecksum() {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_bad_checksum_claim_notice.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidTcnIdChecksumException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_premiumBlocked() {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    EppException thrown = assertThrows(PremiumNameBlockedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_premiumNotAcked_byRegistryRequiringAcking() {
    createTld("example");
    assertThat(Registry.get("example").getPremiumPriceAckRequired()).isTrue();
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_premiumNotAcked_byRegistrarRequiringAcking() {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setPremiumPriceAckRequired(true).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_premiumNotAcked_whenRegistrarAndRegistryRequireAcking() {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(true).build());
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setPremiumPriceAckRequired(true).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_omitFeeExtensionOnLogin_v06() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_omitFeeExtensionOnLogin_v11() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_omitFeeExtensionOnLogin_v12() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v06() {
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v11() {
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v12() {
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_suspendedRegistrarCantCreateDomain() {
    persistContactsAndHosts();
    persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(State.SUSPENDED)
            .build());
    EppException thrown =
        assertThrows(RegistrarMustBeActiveToCreateDomainsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void doFailingDomainNameTest(String domainName, Class<? extends EppException> exception) {
    setEppInput("domain_create_uppercase.xml");
    eppLoader.replaceAll("Example.tld", domainName);
    persistContactsAndHosts();
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_uppercase() {
    doFailingDomainNameTest("Example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_badCharacter() {
    doFailingDomainNameTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_leadingDash() {
    doFailingDomainNameTest("-example.tld", LeadingDashException.class);
  }

  @Test
  public void testFailure_trailingDash() {
    doFailingDomainNameTest("example-.tld", TrailingDashException.class);
  }

  @Test
  public void testFailure_tooLong() {
    doFailingDomainNameTest(Strings.repeat("a", 64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  public void testFailure_leadingDot() {
    doFailingDomainNameTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_leadingDotTld() {
    doFailingDomainNameTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_tooManyParts() {
    doFailingDomainNameTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_tooFewParts() {
    doFailingDomainNameTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_invalidPunycode() {
    // You don't want to know what this string (might?) mean.
    doFailingDomainNameTest("xn--uxa129t5ap4f1h1bc3p.tld", InvalidPunycodeException.class);
  }

  @Test
  public void testFailure_dashesInThirdAndFourthPosition() {
    doFailingDomainNameTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  public void testFailure_tldDoesNotExist() {
    doFailingDomainNameTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  public void testFailure_invalidIdnCodePoints() {
    // ❤☀☆☂☻♞☯.tld
    doFailingDomainNameTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }

  @Test
  public void testFailure_sunriseRegistration() {
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserSunriseRegistration() throws Exception {
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_qlpSunriseRegistration() throws Exception {
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_qlp_sunrise.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  public void testSuccess_qlpSunriseRegistration_withEncodedSignedMark() throws Exception {
    createTld("tld", TldState.SUNRISE);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_registration_qlp_sunrise_encoded_signed_mark.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of("DOMAIN", "test-validate.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT, Flag.SUNRISE));
    assertSunriseLordn("test-validate.tld");
  }

  @Test
  public void testSuccess_qlpSunriseRegistration_withClaimsNotice() throws Exception {
    createTld("tld", TldState.SUNRISE);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_qlp_sunrise_claims_notice.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertClaimsLordn();
  }

  @Test
  public void testFailure_startDateSunriseRegistration_missingSignedMark() {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserStartDateSunriseRegistration_isSuperuser() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_qlpRegistrationSunriseRegistration() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput("domain_create_registration_qlp_start_date_sunrise.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  public void testSuccess_startDateSunriseRegistration_withEncodedSignedMark() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of("DOMAIN", "test-validate.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(Flag.SUNRISE));
    assertSunriseLordn("test-validate.tld");
  }

  /**
   * Test that missing type= argument on launch create works in start-date sunrise.
   *
   * <p>TODO(b/76095570):have the same exact test on end-date sunrise - using the same .xml file -
   * that checks that an application was created.
   */
  @Test
  public void testSuccess_startDateSunriseRegistration_withEncodedSignedMark_noType()
      throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_sunrise_encoded_signed_mark_no_type.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of("DOMAIN", "test-validate.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(Flag.SUNRISE));
    assertSunriseLordn("test-validate.tld");
  }

  /** Tests possible confusion caused by the common start-date and end-date sunrise LaunchPhase. */
  @Test
  public void testFail_sunriseRegistration_withEncodedSignedMark() {
    createTld("tld", TldState.SUNRISE);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise"));
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFail_startDateSunriseRegistration_wrongEncodedSignedMark() {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "wrong.tld", "PHASE", "sunrise"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(NoMarksFoundMatchingDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFail_startDateSunriseRegistration_markNotYetValid() {
    createTld("tld", START_DATE_SUNRISE);
    // If we move now back in time a bit, the mark will not have gone into effect yet.
    clock.setTo(DateTime.parse("2013-08-09T10:05:59Z").minusSeconds(1));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FoundMarkNotYetValidException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFail_startDateSunriseRegistration_markExpired() {
    createTld("tld", START_DATE_SUNRISE);
    // Move time forward to the mark expiration time.
    clock.setTo(DateTime.parse("2017-07-23T22:00:00.000Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FoundMarkExpiredException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_startDateSunriseRegistration_withClaimsNotice() {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_start_date_sunrise_claims_notice.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_sunrushRegistration() {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_registration_sunrush.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_notAuthorizedForTld() {
    createTld("irrelevant", "IRR");
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("irrelevant"))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserSunrushRegistration() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_registration_sunrush.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_qlpSunrushRegistration() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_registration_qlp_sunrush.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  public void testSuccess_qlpSunrushRegistration_withEncodedSignedMark() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_registration_qlp_sunrush_encoded_signed_mark.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of("DOMAIN", "test-validate.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT, Flag.SUNRISE));
    assertSunriseLordn("test-validate.tld");
  }

  @Test
  public void testSuccess_qlpSunrushRegistration_withClaimsNotice() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_qlp_sunrush_claims_notice.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertClaimsLordn();
  }

  @Test
  public void testFailure_landrushRegistration() {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_registration_landrush.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserLandrushRegistration() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_registration_landrush.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_qlpLandrushRegistration() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_registration_qlp_landrush.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  public void testFailure_qlpLandrushRegistration_withEncodedSignedMark() {
    createTld("tld", TldState.LANDRUSH);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_registration_qlp_landrush_encoded_signed_mark.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(SignedMarksOnlyDuringSunriseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_qlpLandrushRegistration_withClaimsNotice() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_qlp_landrush_claims_notice.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertClaimsLordn();
  }

  @Test
  public void testFailure_registrantNotWhitelisted() {
    persistActiveContact("someone");
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("someone"))
            .build());
    RegistrantNotAllowedException thrown =
        assertThrows(RegistrantNotAllowedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("jd1234");
  }

  @Test
  public void testFailure_nameserverNotWhitelisted() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns2.example.net"))
            .build());
    NameserversNotAllowedForTldException thrown =
        assertThrows(NameserversNotAllowedForTldException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns1.example.net");
  }

  @Test
  public void testFailure_emptyNameserverFailsWhitelist() {
    setEppInput("domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("somethingelse.example.net"))
            .build());
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(
            NameserversNotSpecifiedForTldWithNameserverWhitelistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_nameserverAndRegistrantWhitelisted() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("jd1234"))
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.net", "ns2.example.net"))
            .build());
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_domainNameserverRestricted_allNameserversAllowed() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns3.example.net"))
            .build());
    doSuccessfulTest();
  }

  @Test
  public void testFailure_domainNameserverRestricted_noNameservers() {
    setEppInput("domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns3.example.net"))
            .build());
    EppException thrown =
        assertThrows(
            NameserversNotSpecifiedForNameserverRestrictedDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_domainNameserverRestricted_someNameserversDisallowed() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "reserved", "example,NAMESERVER_RESTRICTED,ns2.example.net:ns3.example.net"))
            .build());
    NameserversNotAllowedForDomainException thrown =
        assertThrows(NameserversNotAllowedForDomainException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns1.example.net");
  }

  @Test
  public void testFailure_domainCreateRestricted_domainNotReserved() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setDomainCreateRestricted(true)
            .setReservedLists(
                persistReservedList(
                    "reserved", "lol,NAMESERVER_RESTRICTED,ns1.example.net:ns2.example.net"))
            .build());
    DomainNotAllowedForTldWithCreateRestrictionException thrown =
        assertThrows(DomainNotAllowedForTldWithCreateRestrictionException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("example.tld");
  }

  @Test
  public void testSuccess_domainCreateNotRestricted_domainNotReserved() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "reserved", "lol,NAMESERVER_RESTRICTED,ns1.example.net:ns2.example.net"))
            .build());
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_tldAndDomainNameserversWhitelistBothSatisfied() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setDomainCreateRestricted(true)
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.net", "ns2.example.net", "ns3.example.net"))
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns4.example.net"))
            .build());
    doSuccessfulTest();
  }

  @Test
  public void testFailure_domainNameserversAllowed_tldNameserversDisallowed() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns2.example.net", "ns3.example.net", "ns4.example.net"))
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns3.example.net"))
            .build());
    NameserversNotAllowedForTldException thrown =
        assertThrows(NameserversNotAllowedForTldException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns1.example.net");
  }

  @Test
  public void testFailure_domainNameserversDisallowed_tldNameserversAllowed() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.net", "ns2.example.net", "ns3.example.net"))
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns2.example.net:ns3.example.net:ns4.example.net"))
            .build());
    NameserversNotAllowedForDomainException thrown =
        assertThrows(NameserversNotAllowedForDomainException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns1.example.net");
  }

  @Test
  public void testFailure_tldNameserversAllowed_domainCreateRestricted_domainNotReserved() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setDomainCreateRestricted(true)
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.net", "ns2.example.net", "ns3.example.net"))
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "lol,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns3.example.net"))
            .build());
    DomainNotAllowedForTldWithCreateRestrictionException thrown =
        assertThrows(DomainNotAllowedForTldWithCreateRestrictionException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("example.tld");
  }

  @Test
  public void testSuccess_domainCreateRestricted_serverStatusSet() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setDomainCreateRestricted(true)
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns3.example.net"))
            .build());
    doSuccessfulTest();
    assertThat(reloadResourceByForeignKey().getStatusValues())
        .containsExactly(SERVER_UPDATE_PROHIBITED, SERVER_TRANSFER_PROHIBITED);
  }

  @Test
  public void testSuccess_domainCreateNotRestricted_serverStatusNotSet() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "reserved",
                    "example,NAMESERVER_RESTRICTED,"
                        + "ns1.example.net:ns2.example.net:ns3.example.net"))
            .build());
    doSuccessfulTest();
    assertThat(reloadResourceByForeignKey().getStatusValues()).containsExactly(OK);
  }

  @Test
  public void testFailure_eapFee_combined() {
    setEppInput("domain_create_eap_combined_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    EppException thrown = assertThrows(FeeDescriptionParseException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("No fee description");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_eapFee_description_swapped() {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.6",
            "DESCRIPTION_1",
            "Early Access Period",
            "DESCRIPTION_2",
            "create"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("CREATE");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_eapFee_totalAmountNotMatched() {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "26")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "100")
            .put("DESCRIPTION_3", "renew")
            .put("FEE_3", "55")
            .build());
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected total of USD 126.00");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_eapFee_multipleEAPfees_doNotAddToExpectedValue() {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "26")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "55")
            .put("DESCRIPTION_3", "Early Access Period")
            .put("FEE_3", "55")
            .build());
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected fee of USD 100.00");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_eapFee_multipleEAPfees_addToExpectedValue() throws Exception {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "26")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "55")
            .put("DESCRIPTION_3", "Early Access Period")
            .put("FEE_3", "45")
            .build());
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_eapFee_fullDescription_includingArbitraryExpiryTime() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.6",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period, fee expires: 2022-03-01T00:00:00.000Z"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testFailure_eapFee_description_multipleMatch() {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION", "0.6", "DESCRIPTION_1", "create", "DESCRIPTION_2", "renew transfer"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    EppException thrown = assertThrows(FeeDescriptionMultipleMatchesException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("RENEW, TRANSFER");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_eapFeeApplied_v06() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.6",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_eapFeeApplied_v11() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.11",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  public void testSuccess_eapFeeApplied_v12() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.12",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period"));
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  public void testFailure_domainInEap_failsWithoutFeeExtension() {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    Exception e = assertThrows(FeesRequiredDuringEarlyAccessProgramException.class, this::runFlow);
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Fees must be explicitly acknowledged when creating domains "
                + "during the Early Access Program. The EAP fee is: USD 100.00");
  }

  @Test
  public void testSuccess_eapFee_beforeEntireSchedule() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 10),
                    clock.nowUtc().plusDays(2),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest("tld", "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_eapFee_afterEntireSchedule() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(2),
                    Money.of(USD, 100),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest("tld", "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testFailure_max10Years() {
    setEppInput("domain_create_11_years.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(ExceedsMaxRegistrationYearsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testIcannActivityReportField_getsLogged() throws Exception {
    persistContactsAndHosts();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-create");
    assertTldsFieldLogged("tld");
    // Ensure we log the client ID for srs-dom-create so we can also use it for attempted-adds.
    assertClientIdFieldLogged("TheRegistrar");
  }

  @Test
  public void testIcannTransactionRecord_getsStored() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAddGracePeriodLength(Duration.standardMinutes(9))
            .build());
    runFlow();
    DomainResource domain = reloadResourceByForeignKey();
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertThat(historyEntry.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld",
                historyEntry.getModificationTime().plusMinutes(9),
                TransactionReportField.netAddsFieldFromYears(2),
                1));
  }

  @Test
  public void testIcannTransactionRecord_testTld_notStored() throws Exception {
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder().setTldType(TldType.TEST).build());
    runFlow();
    DomainResource domain = reloadResourceByForeignKey();
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    // No transaction records should be stored for test TLDs
    assertThat(historyEntry.getDomainTransactionRecords()).isEmpty();
  }

  @Test
  public void testEppMetric_isSuccessfullyCreated() throws Exception {
    persistContactsAndHosts();
    runFlow();
    EppMetric eppMetric = getEppMetric();
    assertThat(eppMetric.getCommandName()).hasValue("DomainCreate");
    assertThat(eppMetric.getAttempts()).isEqualTo(1);
  }
}
