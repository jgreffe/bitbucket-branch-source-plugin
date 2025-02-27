/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.bitbucket;

import com.cloudbees.jenkins.plugins.bitbucket.client.BitbucketIntegrationClientFactory;
import com.cloudbees.jenkins.plugins.bitbucket.client.pullrequest.BitbucketPullRequestCommit;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.AbstractBitbucketEndpoint;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketEndpointConfiguration;
import com.cloudbees.jenkins.plugins.bitbucket.impl.extension.BitbucketEnvVarExtension;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.scm.SCM;
import hudson.util.Secret;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.WildcardSCMHeadFilterTrait;
import org.apache.commons.lang.StringUtils;
import org.assertj.core.api.ThrowingConsumer;
import org.jenkinsci.plugins.displayurlapi.ClassicDisplayURLProvider;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.assertj.core.api.Assertions.anyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@WithJenkins
class BitbucketSCMSourceTest {
    private static JenkinsRule j;

    @BeforeAll
    static void init(JenkinsRule rule) {
        j = rule;
    }

    private String testName;

    @BeforeEach
    void setup(TestInfo testInfo) {
        this.testName = testInfo.getTestMethod().get().getName();
    }

    private BitbucketSCMSource load(String dataSet) {
        String path = getClass().getSimpleName() + "/" + dataSet + ".xml";
        URL url = getClass().getResource(path);
        BitbucketSCMSource bss = (BitbucketSCMSource) Jenkins.XSTREAM2.fromXML(url);
        return bss;
    }

    // Also initialize the external endpoint configuration storage for some
    // tests. Relevant XMLs are in a subdir of this class' fixtures.
    private void loadBEC(String dataSet) {
        // Note to use original BitbucketSCMSourceTest::getClass() here to get proper paths
        String path = getClass().getSimpleName() + "/" + BitbucketEndpointConfiguration.class.getSimpleName() + "/" + dataSet + ".xml";
        URL url = getClass().getResource(path);
        BitbucketEndpointConfiguration bec = (BitbucketEndpointConfiguration) Jenkins.XSTREAM2.fromXML(url);
        for (AbstractBitbucketEndpoint abe : bec.getEndpoints()) {
            if (abe != null) {
                BitbucketEndpointConfiguration.get().updateEndpoint(abe);
            }
        }
    }

    @Test
    void modern() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("e4d8c11a-0d24-472f-b86b-4b017c160e9a");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("curl");
        assertThat(instance.getTraits()).isEmpty();
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isTrue();
    }

    @Test
    void basic_cloud_git() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                forkPRTrait(2, true)));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Test
    void basic_server() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket"
                + ".BitbucketSCMNavigator::https://bitbucket.test::DUB::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.test");
        assertThat(instance.getRepoOwner()).isEqualTo("DUB");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bb-beescloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                forkPRTrait(2, true)));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isEqualTo("https://bitbucket.test");
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Test
    void custom_checkout_credentials() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                forkPRTrait(2, true), //
                sshTrait("other-credentials")));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo("other-credentials");
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Issue("JENKINS-45467")
    @Test
    void same_checkout_credentials() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket"
                + ".BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                forkPRTrait(2, true)));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Test
    void exclude_branches() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                forkPRTrait(2, true),
                trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcardTrait -> {
                    assertThat(wildcardTrait.getIncludes()).isEqualTo("*");
                    assertThat(wildcardTrait.getExcludes()).isEqualTo("main");
                })));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEqualTo("main");
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Test
    void limit_branches() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                forkPRTrait(2, true),
                trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcardTrait -> {
                    assertThat(wildcardTrait.getIncludes()).isEqualTo("feature/*");
                    assertThat(wildcardTrait.getExcludes()).isEmpty();
                })));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Test
    void register_hooks() throws Exception {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                forkPRTrait(2, true),
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.ITEM)));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isTrue();
    }

    @Test
    void use_agent_checkout() {
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getId()).isEqualTo("com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator::https://bitbucket.org::cloudbeers::stunning-adventure");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getRepoOwner()).isEqualTo("cloudbeers");
        assertThat(instance.getRepository()).isEqualTo("stunning-adventure");
        assertThat(instance.getCredentialsId()).isEqualTo("bitbucket-cloud");
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, true), //
                originPRTrait(), //
                forkPRTrait(2, true), //
                publicRepoTrait(), //
                webhookTrait(WebhookRegistration.DISABLE), //
                sshTrait(null)));
        // Legacy API
        assertThat(instance.getBitbucketServerUrl()).isNull();
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.ANONYMOUS);
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.isAutoRegisterHook()).isFalse();
    }

    @Test
    void test_that_clone_url_does_not_contains_username() {
        BranchSCMHead head = new BranchSCMHead("master");
        BitbucketPullRequestCommit commit = new BitbucketPullRequestCommit();
        commit.setHash("046d9a3c1532acf4cf08fe93235c00e4d673c1d2");
        commit.setDate(new Date());

        BitbucketSCMSource instance = new BitbucketSCMSource("amuniz", "test-repo");
        BitbucketMockApiFactory.add(instance.getServerUrl(), BitbucketIntegrationClientFactory.getApiMockClient(instance.getServerUrl()));
        SCM scm = instance.build(head, new BitbucketGitSCMRevision(head, commit));
        assertThat(scm).isInstanceOf(GitSCM.class);
        GitSCM gitSCM = (GitSCM) scm;
        assertThat(gitSCM.getUserRemoteConfigs()).isNotEmpty()
            .element(0)
            .satisfies(urc -> {
                assertThat(urc.getUrl()).isEqualTo("https://bitbucket.org/amuniz/test-repos.git");
            });
    }

    @Test
    void verify_envvar() {
        BranchSCMHead head = new BranchSCMHead("master");
        BitbucketPullRequestCommit commit = new BitbucketPullRequestCommit();
        commit.setHash("046d9a3c1532acf4cf08fe93235c00e4d673c1d2");
        commit.setDate(new Date());

        BitbucketSCMSource instance = new BitbucketSCMSource("amuniz", "test-repo");
        BitbucketMockApiFactory.add(instance.getServerUrl(), BitbucketIntegrationClientFactory.getApiMockClient(instance.getServerUrl()));
        SCM scm = instance.build(head, new BitbucketGitSCMRevision(head, commit));
        assertThat(scm).isInstanceOf(GitSCM.class);
        GitSCM gitSCM = (GitSCM) scm;

        assertThat(gitSCM.getExtensions())
            .isNotEmpty()
            .hasAtLeastOneElementOfType(BitbucketEnvVarExtension.class);

        BitbucketEnvVarExtension gitExtension = gitSCM.getExtensions().stream()
            .filter(BitbucketEnvVarExtension.class::isInstance)
            .map(BitbucketEnvVarExtension.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(gitExtension.getOwner()).isEqualTo("amuniz");
        assertThat(gitExtension.getProjectKey()).isEqualTo("PUB");
        assertThat(gitExtension.getServerURL()).isEqualTo(instance.getServerUrl());
        assertThat(gitExtension.getRepository()).isEqualTo("test-repo");
    }

    @Test
    void given__instance__when__setTraits_empty__then__traitsEmpty() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(Collections.<SCMSourceTrait>emptyList());
        assertThat(instance.getTraits()).isEmpty();
    }

    @Test
    void given__instance__when__setTraits__then__traitsSet() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(new BranchDiscoveryTrait(1),
                new WebhookRegistrationTrait(WebhookRegistration.DISABLE)));
        assertThat(instance.getTraits()).allSatisfy(anyOf( //
                branchTrait(true, false), //
                webhookTrait(WebhookRegistration.DISABLE)));
    }

    @Test
    void given__instance__when__setServerUrl__then__urlNormalized() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setServerUrl("https://bitbucket.org:443/foo/../bar/../");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
    }

    @Test
    void given__instance__when__setCredentials_empty__then__credentials_null() {
        BitbucketSCMSource instance = new BitbucketSCMSource( "testing", "test-repo");
        instance.setCredentialsId("");
        assertThat(instance.getCredentialsId()).isNull();
    }

    @Test
    void given__instance__when__setCredentials_null__then__credentials_null() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setCredentialsId("");
        assertThat(instance.getCredentialsId()).isNull();
    }

    @Test
    void given__instance__when__setCredentials__then__credentials_set() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setCredentialsId("test");
        assertThat(instance.getCredentialsId()).isEqualTo("test");
    }

    @Test
    void given__instance__when__setBitbucketServerUrl_null__then__cloudUrlApplied() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setBitbucketServerUrl(null);
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getBitbucketServerUrl()).isNull();
    }

    @Test
    void given__instance__when__setBitbucketServerUrl_value__then__valueApplied() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setBitbucketServerUrl("https://bitbucket.test");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.test");
        assertThat(instance.getBitbucketServerUrl()).isEqualTo("https://bitbucket.test");
    }

    @Test
    void given__instance__when__setBitbucketServerUrl_value__then__valueNormalized() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setBitbucketServerUrl("https://bitbucket.test/foo/bar/../../");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.test");
        assertThat(instance.getBitbucketServerUrl()).isEqualTo("https://bitbucket.test");
    }

    @Test
    void given__instance__when__setBitbucketServerUrl_cloudUrl__then__valueApplied() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setBitbucketServerUrl("https://bitbucket.org");
        assertThat(instance.getServerUrl()).isEqualTo("https://bitbucket.org");
        assertThat(instance.getBitbucketServerUrl()).isNull();
    }

    @Test
    void given__legacyCode__when__setAutoRegisterHook_true__then__traitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new SSHCheckoutTrait("dummy")));
        assertThat(instance.isAutoRegisterHook()).isTrue();
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(WebhookRegistrationTrait.class);
        instance.setAutoRegisterHook(true);
        assertThat(instance.isAutoRegisterHook()).isTrue();
        assertThat(instance.getTraits()).anySatisfy(webhookTrait(WebhookRegistration.ITEM));
    }

    @Test
    void given__legacyCode__when__setAutoRegisterHook_changes__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(new BranchDiscoveryTrait(true, false),
                new SSHCheckoutTrait("dummy")));
        assertThat(instance.isAutoRegisterHook()).isTrue();
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(WebhookRegistrationTrait.class);
        instance.setAutoRegisterHook(false);
        assertThat(instance.isAutoRegisterHook()).isFalse();
        assertThat(instance.getTraits()).anySatisfy(webhookTrait(WebhookRegistration.DISABLE));
    }

    @Test
    void given__legacyCode__when__setAutoRegisterHook_false__then__traitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(new BranchDiscoveryTrait(true, false),
                new SSHCheckoutTrait("dummy"),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.isAutoRegisterHook()).isTrue();
        assertThat(instance.getTraits()).anySatisfy(webhookTrait(WebhookRegistration.SYSTEM));
        instance.setAutoRegisterHook(true);
        assertThat(instance.isAutoRegisterHook()).isTrue();
        assertThat(instance.getTraits()).anySatisfy(webhookTrait(WebhookRegistration.ITEM));
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_SAME__then__noTraitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
        instance.setCheckoutCredentialsId(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_SAME__then__traitRemoved() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM),
                new SSHCheckoutTrait("value")));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo("value");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
            .isInstanceOfSatisfying(SSHCheckoutTrait.class, sshTrait -> assertThat(sshTrait.getCredentialsId()).isEqualTo("value")));
        instance.setCheckoutCredentialsId(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_null__then__noTraitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
        instance.setCheckoutCredentialsId(null);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_null__then__traitRemoved() {
        String credentialsId = "other-credentials";
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM),
                new SSHCheckoutTrait(credentialsId)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(credentialsId);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(credentialsId));
        instance.setCheckoutCredentialsId(null);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_value__then__traitAdded() {
        String credentialsId = "other-credentials";
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
        instance.setCheckoutCredentialsId(credentialsId);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(credentialsId);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(credentialsId));
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_value__then__traitUpdated() {
        String credentialsId = "other-credentials";
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM),
                new SSHCheckoutTrait(null)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.ANONYMOUS);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(null));
        instance.setCheckoutCredentialsId(credentialsId);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(credentialsId);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(credentialsId));
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_ANONYMOUS__then__traitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.SAME);
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
        instance.setCheckoutCredentialsId(BitbucketSCMSource.DescriptorImpl.ANONYMOUS);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.ANONYMOUS);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(null));
    }

    @Test
    void given__legacyCode__when__setCheckoutCredentials_ANONYMOUS__then__traitUpdated() {
        String credentialsId = "other-credentials";
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM),
                new SSHCheckoutTrait(credentialsId)));
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(credentialsId);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(credentialsId));
        instance.setCheckoutCredentialsId(BitbucketSCMSource.DescriptorImpl.ANONYMOUS);
        assertThat(instance.getCheckoutCredentialsId()).isEqualTo(BitbucketSCMSource.DescriptorImpl.ANONYMOUS);
        assertThat(instance.getTraits()).anySatisfy(sshTrait(null));
    }

    @Test
    void given__legacyCode_withoutExcludes__when__setIncludes_default__then__traitRemoved() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("feature/*", ""),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).anySatisfy(wildcardTrait("feature/*", ""));
        instance.setIncludes("*");
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(WildcardSCMHeadFilterTrait.class);
    }

    @Test
    void given__legacyCode_withoutExcludes__when__setIncludes_value__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("feature/*", ""),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("feature/*"); //
                    assertThat(wildcarTrait.getExcludes()).isEmpty(); //
                }));
        instance.setIncludes("bug/*");
        assertThat(instance.getIncludes()).isEqualTo("bug/*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("bug/*"); //
                    assertThat(wildcarTrait.getExcludes()).isEmpty(); //
                }));
    }

    @Test
    void given__legacyCode_withoutTrait__when__setIncludes_value__then__traitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(WildcardSCMHeadFilterTrait.class);
        instance.setIncludes("feature/*");
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEqualTo("");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("feature/*"); //
                    assertThat(wildcarTrait.getExcludes()).isEmpty(); //
                }));
    }

    @Test
    void given__legacyCode_withExcludes__when__setIncludes_default__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("feature/*", "feature/ignore"),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("feature/*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("feature/ignore"); //
                }));
        instance.setIncludes("*");
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("feature/ignore"); //
                }));
    }

    @Test
    void given__legacyCode_withExcludes__when__setIncludes_value__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("feature/*", "feature/ignore"),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("feature/*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("feature/ignore"); //
                }));

        instance.setIncludes("bug/*");
        assertThat(instance.getIncludes()).isEqualTo("bug/*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("bug/*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("feature/ignore"); //
                }));
    }

    @Test
    void given__legacyCode_withoutIncludes__when__setExcludes_default__then__traitRemoved() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("*", "feature/ignore"),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("feature/ignore"); //
                }));
        instance.setExcludes("");
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(SSHCheckoutTrait.class);
    }

    @Test
    void given__legacyCode_withoutIncludes__when__setExcludes_value__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("*", "feature/ignore"),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("feature/ignore"); //
                }));

        instance.setExcludes("bug/ignore");
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEqualTo("bug/ignore");
        assertThat(instance.getTraits()).anySatisfy(trait -> assertThat(trait)
                .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                    assertThat(wildcarTrait.getIncludes()).isEqualTo("*"); //
                    assertThat(wildcarTrait.getExcludes()).isEqualTo("bug/ignore"); //
                }));
    }

    @Test
    void given__legacyCode_withoutTrait__when__setExcludes_value__then__traitAdded() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).doesNotHaveAnyElementsOfTypes(WildcardSCMHeadFilterTrait.class);
        instance.setExcludes("feature/ignore");
        assertThat(instance.getIncludes()).isEqualTo("*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(wildcardTrait("*", "feature/ignore"));
    }

    @Test
    void given__legacyCode_withIncludes__when__setExcludes_default__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("feature/*", "feature/ignore"),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(wildcardTrait("feature/*", "feature/ignore"));

        instance.setExcludes("");
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).anySatisfy(wildcardTrait("feature/*", ""));
    }

    @Test
    void given__legacyCode_withIncludes__when__setExcludes_value__then__traitUpdated() {
        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setTraits(List.of(
                new BranchDiscoveryTrait(true, false),
                new WildcardSCMHeadFilterTrait("feature/*", ""),
                new WebhookRegistrationTrait(WebhookRegistration.SYSTEM)));
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEmpty();
        assertThat(instance.getTraits()).anySatisfy(wildcardTrait("feature/*", ""));

        instance.setExcludes("feature/ignore");
        assertThat(instance.getIncludes()).isEqualTo("feature/*");
        assertThat(instance.getExcludes()).isEqualTo("feature/ignore");
        assertThat(instance.getTraits()).anySatisfy(wildcardTrait("feature/*", "feature/ignore"));
    }

    // NOTE: The tests below require that a BitbucketEndpointConfiguration with
    // expected BB server and J root URLs exists, otherwise a dummy one is
    // instantiated via readResolveServerUrl() in BitbucketSCMSource::readResolve()
    // and then causes readResolve() call stack to revert the object from the
    // properly loaded values (from XML fixtures) into the default Root URL lookup,
    // as coded and intended (config does exist, so we honor it).
    @Test
    void bitbucketJenkinsRootUrl_emptyDefaulted() throws Exception {
        loadBEC(testName);
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo(ClassicDisplayURLProvider.get().getRoot());

        // Verify that an empty custom URL keeps returning the
        // current global root URL (ending with a slash),
        // meaning "current value at the moment when we ask".
        JenkinsLocationConfiguration.get().setUrl("http://localjenkins:80");
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo("http://localjenkins:80/");

        JenkinsLocationConfiguration.get().setUrl("https://ourjenkins.master:8443/ci");
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo("https://ourjenkins.master:8443/ci/");
    }

    @Test
    void bitbucketJenkinsRootUrl_goodAsIs() {
        loadBEC(testName);
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo("http://jenkins.test:8080/");
    }

    @Test
    void bitbucketJenkinsRootUrl_normalized() {
        loadBEC(testName);
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo("https://jenkins.test/");
    }

    @Test
    void bitbucketJenkinsRootUrl_slashed() {
        loadBEC(testName);
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo("https://jenkins.test/jenkins/");
    }

    @Test
    void bitbucketJenkinsRootUrl_notslashed() {
        loadBEC(testName);
        BitbucketSCMSource instance = load(testName);
        assertThat(instance.getEndpointJenkinsRootUrl()).isEqualTo("https://jenkins.test/jenkins/");
    }

    @Test
    void verify_built_scm_with_username_password_authenticator() throws Exception {
        UsernamePasswordCredentialsImpl userCredentials = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "token-id", "desc", "user", "passqword");

        CredentialsStore store = CredentialsProvider.lookupStores(j.getInstance()).iterator().next();
        store.addCredentials(Domain.global(), userCredentials);

        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setCredentialsId(userCredentials.getId());
        BitbucketMockApiFactory.add(instance.getServerUrl(), BitbucketIntegrationClientFactory.getApiMockClient(instance.getServerUrl()));
        BranchSCMHead head = new BranchSCMHead("master");
        GitSCM scm = (GitSCM) instance.build(head);
        assertThat(scm.getUserRemoteConfigs())
            .hasSize(1)
            .first()
            .satisfies(urc -> assertThat(urc.getCredentialsId()).isEqualTo(userCredentials.getId()));

        GitClient c = mock(GitClient.class);
        for (GitSCMExtension ext : scm.getExtensions()) {
            c = ext.decorate(scm, c);
        }
        verify(c, never()).setCredentials(any(StandardUsernameCredentials.class));
        verify(c, never()).addCredentials(anyString(), any(StandardUsernameCredentials.class));
    }

    @Test
    void verify_built_scm_with_token_authenticator() throws Exception {
        StringCredentials tokenCredentials = new StringCredentialsImpl(CredentialsScope.GLOBAL, "token-id", "desc", Secret.fromString("password"));

        CredentialsStore store = CredentialsProvider.lookupStores(j.getInstance()).iterator().next();
        store.addCredentials(Domain.global(), tokenCredentials);

        BitbucketSCMSource instance = new BitbucketSCMSource("testing", "test-repo");
        instance.setCredentialsId(tokenCredentials.getId());
        instance.setOwner(mock(SCMSourceOwner.class));

        BitbucketMockApiFactory.add(instance.getServerUrl(), BitbucketIntegrationClientFactory.getApiMockClient(instance.getServerUrl()));
        BranchSCMHead head = new BranchSCMHead("master");
        GitSCM scm = (GitSCM) instance.build(head);
        assertThat(scm.getUserRemoteConfigs())
            .hasSize(1)
            .first()
            .satisfies(urc -> assertThat(urc.getCredentialsId()).isEqualTo(tokenCredentials.getId()));

        GitClient c = mock(GitClient.class);
        for (GitSCMExtension ext : scm.getExtensions()) {
            c = ext.decorate(scm, c);
        }
        verify(c, never()).setCredentials(any(StandardUsernameCredentials.class));
        verify(c).addCredentials(eq("https://bitbucket.org/amuniz/test-repos.git"), any(StandardUsernameCredentials.class));
    }

    private ThrowingConsumer<SCMSourceTrait> webhookTrait(WebhookRegistration registeredOn) {
        return t -> assertThat(t)
                .isInstanceOfSatisfying(WebhookRegistrationTrait.class, trait -> assertThat(trait.getMode()).isEqualTo(registeredOn));
    }

    private ThrowingConsumer<SCMSourceTrait> sshTrait(String credentialsId) {
        return t -> assertThat(t)
                .isInstanceOfSatisfying(SSHCheckoutTrait.class, trait -> assertThat(trait.getCredentialsId()).isEqualTo(credentialsId));
    }

    private ThrowingConsumer<SCMSourceTrait> originPRTrait() {
        return t -> assertThat(t)
                .isInstanceOfSatisfying(OriginPullRequestDiscoveryTrait.class, trait -> assertThat(trait.getStrategyId()).isEqualTo(2));
    }

    private ThrowingConsumer<SCMSourceTrait> forkPRTrait(int strategyId, boolean trusted) {
        return t -> assertThat(t)
                .isInstanceOfSatisfying(ForkPullRequestDiscoveryTrait.class, trait -> { //
                    assertThat(trait.getStrategyId()).isEqualTo(2); //
                    if (trusted) {
                        assertThat(trait.getTrust()).isInstanceOf(ForkPullRequestDiscoveryTrait.TrustEveryone.class);
                    }
                });
    }

    private ThrowingConsumer<SCMSourceTrait> branchTrait(boolean buildBranch, boolean buildPR) {
        return t -> assertThat(t)
                .isInstanceOfSatisfying(BranchDiscoveryTrait.class, trait -> { //
                    if (buildBranch) {
                        assertThat(trait.isBuildBranch()).isTrue();
                    }
                    if (buildPR) {
                        assertThat(trait.isBuildBranchesWithPR()).isTrue();
                    }
                });
    }

    private ThrowingConsumer<? super SCMSourceTrait> wildcardTrait(String includePattern, String excludePattern) {
        return trait -> assertThat(trait)
            .isInstanceOfSatisfying(WildcardSCMHeadFilterTrait.class, wildcarTrait -> { //
                assertThat(wildcarTrait.getIncludes()).isEqualTo(includePattern); //
                if (StringUtils.isBlank(excludePattern)) {
                    assertThat(wildcarTrait.getExcludes()).isEmpty();
                } else {
                    assertThat(wildcarTrait.getExcludes()).isEqualTo(excludePattern);
                }
            });
    }

    private ThrowingConsumer<SCMSourceTrait> publicRepoTrait() {
        return trait -> assertThat(trait).isInstanceOf(PublicRepoPullRequestFilterTrait.class);
    }

}
