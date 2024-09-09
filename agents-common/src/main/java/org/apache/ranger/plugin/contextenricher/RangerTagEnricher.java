/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.contextenricher;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.lang.StringUtils;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerResourceDef;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.model.validation.RangerServiceDefHelper;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest.ResourceElementMatchingScope;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest.ResourceMatchingScope;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerResourceTrie;
import org.apache.ranger.plugin.policyresourcematcher.RangerDefaultPolicyResourceMatcher;
import org.apache.ranger.plugin.policyresourcematcher.RangerPolicyResourceMatcher;
import org.apache.ranger.plugin.policyresourcematcher.RangerResourceEvaluator;
import org.apache.ranger.plugin.util.DownloadTrigger;
import org.apache.ranger.plugin.util.DownloaderTask;
import org.apache.ranger.plugin.service.RangerAuthContext;
import org.apache.ranger.plugin.util.RangerAccessRequestUtil;
import org.apache.ranger.plugin.util.RangerCommonConstants;
import org.apache.ranger.plugin.util.RangerPerfTracer;
import org.apache.ranger.plugin.util.RangerReadWriteLock;
import org.apache.ranger.plugin.util.RangerResourceEvaluatorsRetriever;
import org.apache.ranger.plugin.util.RangerServiceNotFoundException;
import org.apache.ranger.plugin.util.RangerServiceTagsDeltaUtil;
import org.apache.ranger.plugin.util.ServiceDefUtil;
import org.apache.ranger.plugin.util.ServiceTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RangerTagEnricher extends RangerAbstractContextEnricher {
	private static final Logger LOG = LoggerFactory.getLogger(RangerTagEnricher.class);

	private static final Logger PERF_CONTEXTENRICHER_INIT_LOG = RangerPerfTracer.getPerfLogger("contextenricher.init");
	private static final Logger PERF_TRIE_OP_LOG              = RangerPerfTracer.getPerfLogger("resourcetrie.retrieval");
	private static final Logger PERF_SET_SERVICETAGS_LOG      = RangerPerfTracer.getPerfLogger("tagenricher.setservicetags");
	private static final Logger PERF_SERVICETAGS_RETRIEVAL_LOG = RangerPerfTracer.getPerfLogger("tagenricher.tags.retrieval");

	private static final String TAG_REFRESHER_POLLINGINTERVAL_OPTION = "tagRefresherPollingInterval";
	public static final String TAG_RETRIEVER_CLASSNAME_OPTION        = "tagRetrieverClassName";
	private static final String TAG_DISABLE_TRIE_PREFILTER_OPTION    = "disableTrieLookupPrefilter";

	private RangerTagRefresher                 tagRefresher;
	private RangerTagRetriever                 tagRetriever;
	private boolean                            disableTrieLookupPrefilter;
	private EnrichedServiceTags                enrichedServiceTags;
	private boolean                            disableCacheIfServiceNotFound = true;
	private boolean                            dedupStrings                  = true;
	private Timer                              tagDownloadTimer;
	private RangerServiceDefHelper             serviceDefHelper;

	private final BlockingQueue<DownloadTrigger> tagDownloadQueue = new LinkedBlockingQueue<>();
	private final RangerReadWriteLock            lock             = new RangerReadWriteLock(false);
	private final CachedResourceEvaluators       cache            = new CachedResourceEvaluators();


	@Override
	public void init() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.init()");
		}

		super.init();

		String propertyPrefix        = getPropertyPrefix();
		String tagRetrieverClassName = getOption(TAG_RETRIEVER_CLASSNAME_OPTION);
		long   pollingIntervalMs     = getLongOption(TAG_REFRESHER_POLLINGINTERVAL_OPTION, 60 * 1000L);

		dedupStrings               = getBooleanConfig(propertyPrefix + ".dedup.strings", true);
		disableTrieLookupPrefilter = getBooleanOption(TAG_DISABLE_TRIE_PREFILTER_OPTION, false);
		serviceDefHelper           = new RangerServiceDefHelper(serviceDef, false);

		if (StringUtils.isNotBlank(tagRetrieverClassName)) {

			try {
				@SuppressWarnings("unchecked")
				Class<RangerTagRetriever> tagRetriverClass = (Class<RangerTagRetriever>) Class.forName(tagRetrieverClassName);

				tagRetriever = tagRetriverClass.newInstance();

			} catch (ClassNotFoundException exception) {
				LOG.error("Class " + tagRetrieverClassName + " not found, exception=" + exception);
			} catch (ClassCastException exception) {
				LOG.error("Class " + tagRetrieverClassName + " is not a type of RangerTagRetriever, exception=" + exception);
			} catch (IllegalAccessException exception) {
				LOG.error("Class " + tagRetrieverClassName + " illegally accessed, exception=" + exception);
			} catch (InstantiationException exception) {
				LOG.error("Class " + tagRetrieverClassName + " could not be instantiated, exception=" + exception);
			}

			if (tagRetriever != null) {
				disableCacheIfServiceNotFound = getBooleanConfig(propertyPrefix + ".disable.cache.if.servicenotfound", true);
				String cacheDir      = getConfig(propertyPrefix + ".policy.cache.dir", null);
				String cacheFilename = String.format("%s_%s_tag.json", appId, serviceName);

				cacheFilename = cacheFilename.replace(File.separatorChar,  '_');
				cacheFilename = cacheFilename.replace(File.pathSeparatorChar,  '_');

				String cacheFile = cacheDir == null ? null : (cacheDir + File.separator + cacheFilename);

				createLock();

				tagRetriever.setServiceName(serviceName);
				tagRetriever.setServiceDef(serviceDef);
				tagRetriever.setAppId(appId);
				tagRetriever.setPluginConfig(getPluginConfig());
				tagRetriever.setPluginContext(getPluginContext());
				tagRetriever.init(enricherDef.getEnricherOptions());

				tagRefresher = new RangerTagRefresher(tagRetriever, this, -1L, tagDownloadQueue, cacheFile);
				LOG.info("Created RangerTagRefresher Thread(" + tagRefresher.getName() + ")");

				try {
					tagRefresher.populateTags();
				} catch (Throwable exception) {
					LOG.error("Exception when retrieving tag for the first time for this enricher", exception);
				}
				tagRefresher.setDaemon(true);
				tagRefresher.startRefresher();

				tagDownloadTimer = new Timer("policyDownloadTimer", true);

				try {
					tagDownloadTimer.schedule(new DownloaderTask(tagDownloadQueue), pollingIntervalMs, pollingIntervalMs);
					if (LOG.isDebugEnabled()) {
						LOG.debug("Scheduled tagDownloadRefresher to download tags every " + pollingIntervalMs + " milliseconds");
					}
				} catch (IllegalStateException exception) {
					LOG.error("Error scheduling tagDownloadTimer:", exception);
					LOG.error("*** Tags will NOT be downloaded every " + pollingIntervalMs + " milliseconds ***");
					tagDownloadTimer = null;
				}
			}
		} else {
			LOG.error("No value specified for " + TAG_RETRIEVER_CLASSNAME_OPTION + " in the RangerTagEnricher options");
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.init()");
		}
	}

	@Override
	public void enrich(RangerAccessRequest request) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.enrich(" + request + ")");
		}

		enrich(request, null);

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.enrich(" + request + ")");
		}
	}

	@Override
	public void enrich(RangerAccessRequest request, Object dataStore) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.enrich(" + request + ") with dataStore:[" + dataStore + "]");
		}

		final Set<RangerTagForEval> matchedTags;

		try (RangerReadWriteLock.RangerLock readLock = this.lock.getReadLock()) {

			if (LOG.isDebugEnabled()) {
				if (readLock.isLockingEnabled()) {
					LOG.debug("Acquired lock - " + readLock);
				}
			}

			final EnrichedServiceTags enrichedServiceTags;

			if (dataStore instanceof EnrichedServiceTags) {
				enrichedServiceTags = (EnrichedServiceTags) dataStore;
			} else {
				enrichedServiceTags = this.enrichedServiceTags;

				if (dataStore != null) {
					LOG.warn("Incorrect type of dataStore :[" + dataStore.getClass().getName() + "], falling back to original enrich");
				}
			}

			matchedTags = enrichedServiceTags == null ? null : findMatchingTags(request, enrichedServiceTags);

			RangerAccessRequestUtil.setRequestTagsInContext(request.getContext(), matchedTags);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.enrich(" + request + ") with dataStore:[" + dataStore + "]): tags count=" + (matchedTags == null ? 0 : matchedTags.size()));
		}
	}
	/*
	 * This class implements a cache of result of look-up of keyset of policy-resources for each of the collections of hierarchies
	 * for policy types: access, datamask and rowfilter. If a keyset is examined for validity in a hierarchy of a policy-type,
	 * then that record is maintained in this cache for later look-up.
	 *
	 * The basic idea is that with a large number of tagged service-resources, this cache will speed up performance as well as put
	 * a cap on the upper bound because it is expected	that the cardinality of set of all possible keysets for all resource-def
	 * combinations in a service-def will be much smaller than the number of service-resources.
	 */

	static public class ResourceHierarchies {
		private final Map<Collection<String>, Boolean> accessHierarchies    = new HashMap<>();
		private final Map<Collection<String>, Boolean> dataMaskHierarchies  = new HashMap<>();
		private final Map<Collection<String>, Boolean> rowFilterHierarchies = new HashMap<>();

		Boolean isValidHierarchy(int policyType, Collection<String> resourceKeys) {
			switch (policyType) {
				case RangerPolicy.POLICY_TYPE_ACCESS:
					return accessHierarchies.get(resourceKeys);
				case RangerPolicy.POLICY_TYPE_DATAMASK:
					return dataMaskHierarchies.get(resourceKeys);
				case RangerPolicy.POLICY_TYPE_ROWFILTER:
					return rowFilterHierarchies.get(resourceKeys);
				default:
					return null;
			}
		}

		void addHierarchy(int policyType, Collection<String> resourceKeys, Boolean isValid) {
			switch (policyType) {
				case RangerPolicy.POLICY_TYPE_ACCESS:
					accessHierarchies.put(resourceKeys, isValid);
					break;
				case RangerPolicy.POLICY_TYPE_DATAMASK:
					dataMaskHierarchies.put(resourceKeys, isValid);
					break;
				case RangerPolicy.POLICY_TYPE_ROWFILTER:
					rowFilterHierarchies.put(resourceKeys, isValid);
					break;
				default:
					LOG.error("unknown policy-type " + policyType);
					break;
			}
		}
	}

	public void setServiceTags(final ServiceTags serviceTags) {
		boolean rebuildOnlyIndex = false;
		setServiceTags(serviceTags, rebuildOnlyIndex);
	}

	protected void setServiceTags(final ServiceTags serviceTags, final boolean rebuildOnlyIndex) {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.setServiceTags(serviceTags=" + serviceTags + ", rebuildOnlyIndex=" + rebuildOnlyIndex + ")");
		}

		try (RangerReadWriteLock.RangerLock writeLock = this.lock.getWriteLock()) {

			if (LOG.isDebugEnabled()) {
				if (writeLock.isLockingEnabled()) {
					LOG.debug("Acquired lock - " + writeLock);
				}
			}
			RangerPerfTracer perf = null;

			if(RangerPerfTracer.isPerfTraceEnabled(PERF_SET_SERVICETAGS_LOG)) {
				perf = RangerPerfTracer.getPerfTracer(PERF_SET_SERVICETAGS_LOG, "RangerTagEnricher.setServiceTags(newTagVersion=" + serviceTags.getTagVersion() + ",isDelta=" + serviceTags.getIsDelta() + ")");
			}

			if (serviceTags == null) {
				LOG.info("ServiceTags is null for service " + serviceName);
				enrichedServiceTags = null;
			} else {
				if (dedupStrings) {
					serviceTags.dedupStrings();
				}

				if (!serviceTags.getIsDelta()) {
					if (serviceTags.getIsTagsDeduped()) {
						final int countOfDuplicateTags = serviceTags.dedupTags();

						LOG.info("Number of duplicate tags removed from the received serviceTags:[" + countOfDuplicateTags + "]. Number of tags in the de-duplicated serviceTags :[" + serviceTags.getTags().size() + "].");
					}
					processServiceTags(serviceTags);
				} else {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Received service-tag deltas:" + serviceTags);
					}
					ServiceTags oldServiceTags = enrichedServiceTags != null ? enrichedServiceTags.getServiceTags() : new ServiceTags();
					ServiceTags allServiceTags = rebuildOnlyIndex ? oldServiceTags : RangerServiceTagsDeltaUtil.applyDelta(oldServiceTags, serviceTags, serviceTags.getIsTagsDeduped());

					if (serviceTags.getTagsChangeExtent() == ServiceTags.TagsChangeExtent.NONE) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("No change to service-tags other than version change");
						}
					} else {
						if (serviceTags.getTagsChangeExtent() != ServiceTags.TagsChangeExtent.TAGS) {
							Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> trieMap;

							if (enrichedServiceTags == null) {
								trieMap = new HashMap<>();
							} else {
								trieMap = writeLock.isLockingEnabled() ? enrichedServiceTags.getServiceResourceTrie() : copyServiceResourceTrie();
							}

							processServiceTagDeltas(serviceTags, allServiceTags, trieMap);
						} else {
							if (LOG.isDebugEnabled()) {
								LOG.debug("Delta contains only tag attribute changes");
							}
							List<RangerServiceResourceMatcher> resourceMatchers = enrichedServiceTags != null ? enrichedServiceTags.getServiceResourceMatchers() : new ArrayList<>();
							Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> serviceResourceTrie = enrichedServiceTags != null ? enrichedServiceTags.getServiceResourceTrie() : new HashMap<>();
							enrichedServiceTags = new EnrichedServiceTags(allServiceTags, resourceMatchers, serviceResourceTrie);
						}
					}
				}

			}
			setEnrichedServiceTagsInPlugin();

			cache.clearCache();

			RangerPerfTracer.logAlways(perf);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.setServiceTags(serviceTags=" + serviceTags + ", rebuildOnlyIndex=" + rebuildOnlyIndex + ")");
		}

	}

	public Long getServiceTagsVersion() {
		EnrichedServiceTags localEnrichedServiceTags = enrichedServiceTags;
		return localEnrichedServiceTags != null ? localEnrichedServiceTags.getServiceTags().getTagVersion() : -1L;
	}

	protected Long getResourceTrieVersion() {
		EnrichedServiceTags localEnrichedServiceTags = enrichedServiceTags;
		return localEnrichedServiceTags != null ? localEnrichedServiceTags.getResourceTrieVersion() : -1L;
	}

	@Override
	public boolean preCleanup() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.preCleanup()");
		}

		super.preCleanup();

		Timer tagDownloadTimer = this.tagDownloadTimer;
		this.tagDownloadTimer = null;

		if (tagDownloadTimer != null) {
			tagDownloadTimer.cancel();
		}

		RangerTagRefresher tagRefresher = this.tagRefresher;
		this.tagRefresher = null;

		if (tagRefresher != null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Trying to clean up RangerTagRefresher(" + tagRefresher.getName() + ")");
			}
			tagRefresher.cleanup();
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.preCleanup() : result=" + true);
		}
		return true;
	}

	public void syncTagsWithAdmin(final DownloadTrigger token) throws InterruptedException {
		tagDownloadQueue.put(token);
		token.waitForCompletion();
	}

	public EnrichedServiceTags getEnrichedServiceTags() {
		return enrichedServiceTags;
	}

	protected RangerReadWriteLock createLock() {
		String             propertyPrefix        = getPropertyPrefix();
		RangerPluginConfig config                = getPluginConfig();
		boolean            deltasEnabled         = config != null && config.getBoolean(propertyPrefix + RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_TAG_DELTA, RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_TAG_DELTA_DEFAULT);
		boolean            inPlaceUpdatesEnabled = config != null && config.getBoolean(propertyPrefix + RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_IN_PLACE_TAG_UPDATES, RangerCommonConstants.PLUGIN_CONFIG_SUFFIX_IN_PLACE_TAG_UPDATES_DEFAULT);
		boolean            useReadWriteLock      = deltasEnabled && inPlaceUpdatesEnabled;

		LOG.info("Policy-Engine will" + (useReadWriteLock ? " " : " not ") + "use read-write locking to update tags in place when tag-deltas are provided");

		return new RangerReadWriteLock(useReadWriteLock);

	}
	private void processServiceTags(ServiceTags serviceTags) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Processing all service-tags");
		}

		if (CollectionUtils.isEmpty(serviceTags.getServiceResources())) {
			LOG.info("There are no tagged resources for service " + serviceName);
			enrichedServiceTags = null;
		} else {
			ResourceHierarchies                hierarchies      = new ResourceHierarchies();
			List<RangerServiceResourceMatcher> resourceMatchers = new ArrayList<>();
			List<RangerServiceResource>        serviceResources = serviceTags.getServiceResources();

			for (ListIterator<RangerServiceResource> iter = serviceResources.listIterator(); iter.hasNext(); ) {
				RangerServiceResource        serviceResource        = iter.next();
				RangerServiceResourceMatcher serviceResourceMatcher = createRangerServiceResourceMatcher(serviceResource, serviceDefHelper, hierarchies, getPluginContext());

				if (serviceResourceMatcher != null) {
					resourceMatchers.add(serviceResourceMatcher);
				} else {
					iter.remove();

					List<Long> tags = serviceTags.getResourceToTagIds().remove(serviceResource.getId());

					LOG.warn("Invalid resource [" + serviceResource + "]: failed to create resource-matcher. Ignoring " + (tags != null ? tags.size() : 0) + " tags associated with the resource");
				}
			}

			Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> serviceResourceTrie = null;

			if (!disableTrieLookupPrefilter) {
				serviceResourceTrie = new HashMap<>();

				for (RangerResourceDef resourceDef : serviceDef.getResources()) {
					serviceResourceTrie.put(resourceDef.getName(), new RangerResourceTrie(resourceDef, resourceMatchers, getPolicyEngineOptions().optimizeTagTrieForRetrieval, getPolicyEngineOptions().optimizeTagTrieForSpace, null));
				}
			}
			enrichedServiceTags = new EnrichedServiceTags(serviceTags, resourceMatchers, serviceResourceTrie);
		}
	}

	private void processServiceTagDeltas(ServiceTags deltas, ServiceTags allServiceTags, Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> serviceResourceTrie) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Delta contains changes other than tag attribute changes, [" + deltas.getTagsChangeExtent() + "]");
		}

		boolean                            isInError        = false;

		ResourceHierarchies                hierarchies      = new ResourceHierarchies();
		List<RangerServiceResourceMatcher> resourceMatchers = new ArrayList<>();

		if (enrichedServiceTags != null) {
			resourceMatchers.addAll(enrichedServiceTags.getServiceResourceMatchers());
		}

		List<RangerServiceResource> changedServiceResources = deltas.getServiceResources();

		for (RangerServiceResource serviceResource : changedServiceResources) {
			final boolean removedOldServiceResource = MapUtils.isEmpty(serviceResource.getResourceElements()) || removeOldServiceResource(serviceResource, resourceMatchers, serviceResourceTrie);

			if (removedOldServiceResource) {
				if (!StringUtils.isEmpty(serviceResource.getResourceSignature())) {
					RangerServiceResourceMatcher resourceMatcher = createRangerServiceResourceMatcher(serviceResource, serviceDefHelper, hierarchies, getPluginContext());

					if (resourceMatcher != null) {
						for (RangerResourceDef resourceDef : serviceDef.getResources()) {
							RangerPolicyResource                             policyResource = serviceResource.getResourceElements().get(resourceDef.getName());
							RangerResourceTrie<RangerServiceResourceMatcher> trie           = serviceResourceTrie.get(resourceDef.getName());

							if (LOG.isDebugEnabled()) {
								LOG.debug("Trying to add resource-matcher to " + (trie == null ? "new" : "existing") + " trie for " + resourceDef.getName());
							}

							if (trie != null) {
								trie.add(policyResource, resourceMatcher);
								trie.wrapUpUpdate();

								if (LOG.isDebugEnabled()) {
									LOG.debug("Added resource-matcher for policy-resource:[" + policyResource + "]");
								}
							} else {
								trie = new RangerResourceTrie<>(resourceDef, Collections.singletonList(resourceMatcher), getPolicyEngineOptions().optimizeTagTrieForRetrieval, getPolicyEngineOptions().optimizeTagTrieForSpace, null);
								serviceResourceTrie.put(resourceDef.getName(), trie);
							}
						}
						resourceMatchers.add(resourceMatcher);
					} else {
						LOG.error("Could not create resource-matcher for resource: [" + serviceResource + "]. Should NOT happen!!");
						LOG.error("Setting tagVersion to -1 to ensure that in the next download all tags are downloaded");
						isInError = true;
					}
				} else {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Service-resource:[id=" + serviceResource.getId() + "] is deleted as its resource-signature is empty. No need to create it!");
					}
				}
			} else {
				isInError = true;
			}
			if (isInError) {
				break;
			}
		}

		if (isInError) {
			LOG.error("Error in processing tag-deltas. Will continue to use old tags");
			deltas.setTagVersion(-1L);
		} else {
			for (Map.Entry<String, RangerResourceTrie<RangerServiceResourceMatcher>> entry : serviceResourceTrie.entrySet()) {
				entry.getValue().wrapUpUpdate();
			}
			enrichedServiceTags = new EnrichedServiceTags(allServiceTags, resourceMatchers, serviceResourceTrie);
		}
	}

	private boolean removeOldServiceResource(RangerServiceResource serviceResource, List<RangerServiceResourceMatcher> resourceMatchers, Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> resourceTries) {
		boolean ret = true;

		if (enrichedServiceTags != null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Removing service-resource:[" + serviceResource + "] from trie-map");
			}

			RangerAccessResourceImpl accessResource = new RangerAccessResourceImpl();

			for (Map.Entry<String, RangerPolicyResource> entry : serviceResource.getResourceElements().entrySet()) {
				accessResource.setValue(entry.getKey(), entry.getValue().getValues());
			}

			if (LOG.isDebugEnabled()) {
				LOG.debug("RangerAccessResource:[" + accessResource + "] created to represent service-resource[" + serviceResource + "] to find evaluators from trie-map");
			}

			RangerAccessRequestImpl request = new RangerAccessRequestImpl();
			request.setResource(accessResource);

			Collection<RangerServiceResourceMatcher> oldMatchers = getEvaluators(request, enrichedServiceTags);

			if (LOG.isDebugEnabled()) {
				LOG.debug("Found [" + oldMatchers + "] matchers for service-resource[" + serviceResource + "]");
			}

			if (CollectionUtils.isNotEmpty(oldMatchers)) {
				List<RangerServiceResourceMatcher> notMatched = new ArrayList<>();

				for (RangerServiceResourceMatcher resourceMatcher : oldMatchers) {
					final RangerPolicyResourceMatcher.MatchType matchType = resourceMatcher.getMatchType(accessResource, request.getResourceElementMatchingScopes(), request.getContext());

					if (LOG.isDebugEnabled()) {
						LOG.debug("resource:[" + accessResource + ", MatchType:[" + matchType + "]");
					}

					if (matchType != RangerPolicyResourceMatcher.MatchType.SELF) {
						notMatched.add(resourceMatcher);
					}
				}

				if (LOG.isDebugEnabled()) {
					LOG.debug("oldMatchers : [" + notMatched + "] do not match resource:[" + accessResource + "] exactly and will be discarded");
				}

				oldMatchers.removeAll(notMatched);
			}

			for (RangerServiceResourceMatcher matcher : oldMatchers) {
				for (RangerResourceDef resourceDef : serviceDef.getResources()) {
					String                                           resourceDefName = resourceDef.getName();
					RangerResourceTrie<RangerServiceResourceMatcher> trie            = resourceTries.get(resourceDefName);

					if (trie != null) {
						trie.delete(serviceResource.getResourceElements().get(resourceDefName), matcher);
					} else {
						LOG.error("Cannot find resourceDef with name:[" + resourceDefName + "]. Should NOT happen!!");
						LOG.error("Setting tagVersion to -1 to ensure that in the next download all tags are downloaded");
						ret = false;
						break;
					}
				}
			}

			if (ret) {
				resourceMatchers.removeAll(oldMatchers);

				if (LOG.isDebugEnabled()) {
					LOG.debug("Found and removed [" + oldMatchers + "] matchers for service-resource[" + serviceResource + "] from trie-map");
				}
			}
		}
		return ret;
	}

	static public RangerServiceResourceMatcher createRangerServiceResourceMatcher(RangerServiceResource serviceResource, RangerServiceDefHelper serviceDefHelper, ResourceHierarchies hierarchies, RangerPluginContext pluginContext) {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> createRangerServiceResourceMatcher(serviceResource=" + serviceResource + ")");
		}

		RangerServiceResourceMatcher ret = null;

		final Collection<String> resourceKeys = serviceResource.getResourceElements().keySet();

		for (int policyType : RangerPolicy.POLICY_TYPES) {
			Boolean isValidHierarchy = hierarchies.isValidHierarchy(policyType, resourceKeys);
			if (isValidHierarchy == null) { // hierarchy not yet validated
				isValidHierarchy = Boolean.FALSE;

				for (List<RangerResourceDef> hierarchy : serviceDefHelper.getResourceHierarchies(policyType)) {
					if (serviceDefHelper.hierarchyHasAllResources(hierarchy, resourceKeys)) {
						isValidHierarchy = Boolean.TRUE;

						break;
					}
				}

				hierarchies.addHierarchy(policyType, resourceKeys, isValidHierarchy);
			}

			if (isValidHierarchy) {
				RangerDefaultPolicyResourceMatcher matcher = new RangerDefaultPolicyResourceMatcher();

				matcher.setServiceDef(serviceDefHelper.getServiceDef());
				matcher.setPolicyResources(serviceResource.getResourceElements(), policyType);
				matcher.setPluginContext(pluginContext);

				if (LOG.isDebugEnabled()) {
					LOG.debug("RangerTagEnricher.setServiceTags() - Initializing matcher with (resource=" + serviceResource
							+ ", serviceDef=" + serviceDefHelper.getServiceDef() + ")");

				}
				matcher.setServiceDefHelper(serviceDefHelper);
				matcher.init();

				ret = new RangerServiceResourceMatcher(serviceResource, matcher);
				break;
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== createRangerServiceResourceMatcher(serviceResource=" + serviceResource + ") : [" + ret + "]");
		}
		return ret;

	}

	private void setEnrichedServiceTagsInPlugin() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> setEnrichedServiceTagsInPlugin()");
		}

		RangerAuthContext authContext = getAuthContext();

		if (authContext != null) {
			authContext.addOrReplaceRequestContextEnricher(this, enrichedServiceTags);

			notifyAuthContextChanged();
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== setEnrichedServiceTagsInPlugin()");
		}
	}

	private Set<RangerTagForEval> findMatchingTags(final RangerAccessRequest request, EnrichedServiceTags dataStore) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.findMatchingTags(" + request + ")");
		}

		// To minimize chance for race condition between Tag-Refresher thread and access-evaluation thread
		final EnrichedServiceTags enrichedServiceTags = dataStore != null ? dataStore : this.enrichedServiceTags;

		Set<RangerTagForEval> ret = null;

		RangerAccessResource resource = request.getResource();

		RangerPerfTracer perf = null;

		if (RangerPerfTracer.isPerfTraceEnabled(PERF_SERVICETAGS_RETRIEVAL_LOG)) {
			perf = RangerPerfTracer.getPerfTracer(PERF_SERVICETAGS_RETRIEVAL_LOG, "RangerTagEnricher.findMatchingTags=" + resource.getAsString() + ")");
		}

		if ((resource == null || resource.getKeys() == null || resource.getKeys().isEmpty()) && request.isAccessTypeAny()) {
			ret = enrichedServiceTags.getTagsForEmptyResourceAndAnyAccess();
		} else {

			final Collection<RangerServiceResourceMatcher> serviceResourceMatchers = getEvaluators(request, enrichedServiceTags);

			if (CollectionUtils.isNotEmpty(serviceResourceMatchers)) {
				for (RangerServiceResourceMatcher resourceMatcher : serviceResourceMatchers) {

					final RangerPolicyResourceMatcher.MatchType matchType = resourceMatcher.getMatchType(resource, request.getResourceElementMatchingScopes(), request.getContext());

					if (LOG.isDebugEnabled()) {
						LOG.debug("resource:[" + resource + ", MatchType:[" + matchType + "]");
					}

					final boolean isMatched;

					if (request.isAccessTypeAny()) {
						isMatched = matchType != RangerPolicyResourceMatcher.MatchType.NONE;
					} else if (request.getResourceMatchingScope() == ResourceMatchingScope.SELF_OR_DESCENDANTS) {
						isMatched = matchType != RangerPolicyResourceMatcher.MatchType.NONE;
					} else {
						isMatched = matchType == RangerPolicyResourceMatcher.MatchType.SELF || matchType == RangerPolicyResourceMatcher.MatchType.SELF_AND_ALL_DESCENDANTS || matchType == RangerPolicyResourceMatcher.MatchType.ANCESTOR;
					}

					if (isMatched) {
						if (ret == null) {
							ret = new HashSet<>();
						}
						ret.addAll(getTagsForServiceResource(request.getAccessTime(), enrichedServiceTags.getServiceTags(), resourceMatcher.getServiceResource(), matchType));
					}

				}
			}
		}

		RangerPerfTracer.logAlways(perf);

		if (CollectionUtils.isEmpty(ret)) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("RangerTagEnricher.findMatchingTags(" + resource + ") - No tags Found ");
			}
		} else {
			if (LOG.isDebugEnabled()) {
				LOG.debug("RangerTagEnricher.findMatchingTags(" + resource + ") - " + ret.size() + " tags Found ");
			}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.findMatchingTags(" + request + ")");
		}

		return ret;
	}

	private static class CachedResourceEvaluators {
		private final Map<String, Map<Map<String, ResourceElementMatchingScope>, Collection<RangerServiceResourceMatcher>>> cache     = new HashMap<>();
		private final RangerReadWriteLock                                                                                   cacheLock = new RangerReadWriteLock(true);

		CachedResourceEvaluators() {}

		Collection<RangerServiceResourceMatcher> getEvaluators(String resourceKey, Map<String, ResourceElementMatchingScope> scopes) {
			Collection<RangerServiceResourceMatcher> ret;

			try (RangerReadWriteLock.RangerLock ignored = cacheLock.getReadLock()) {
				ret = cache.getOrDefault(resourceKey, Collections.emptyMap()).get(scopes);
			}

			return ret;
		}

		void cacheEvaluators(String resource, Map<String, ResourceElementMatchingScope> scopes, Collection<RangerServiceResourceMatcher> evaluators) {
			try (RangerReadWriteLock.RangerLock ignored = cacheLock.getWriteLock()) {
				cache.computeIfAbsent(resource, k -> new HashMap<>()).put(scopes, evaluators);
			}
		}

		void clearCache() {
			try (RangerReadWriteLock.RangerLock ignored = cacheLock.getWriteLock()) {
				cache.clear();
			}
		}
	}

	private Collection<RangerServiceResourceMatcher> getEvaluators(RangerAccessRequest request, EnrichedServiceTags enrichedServiceTags) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerTagEnricher.getEvaluators(request=" + request + ")");
		}

		Collection<RangerServiceResourceMatcher>                            ret                 = null;
		final RangerAccessResource                                          resource            = request.getResource();
		final Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> serviceResourceTrie = enrichedServiceTags.getServiceResourceTrie();

		if (resource == null || resource.getKeys() == null || resource.getKeys().isEmpty() || serviceResourceTrie == null) {
			ret = enrichedServiceTags.getServiceResourceMatchers();
		} else {
			RangerPerfTracer perf = null;

			if (RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_OP_LOG)) {
				perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_OP_LOG, "RangerTagEnricher.getEvaluators(resource=" + resource.getAsString() + ")");
			}

			final Predicate predicate = excludeDescendantMatches(request) ? new SelfOrAncestorPredicate(serviceDefHelper.getResourceDef(resource.getLeafName())) : null;

			if (predicate != null) {
				ret = cache.getEvaluators(resource.getCacheKey(), request.getResourceElementMatchingScopes());
			}

			if (ret == null) {
				ret = RangerResourceEvaluatorsRetriever.getEvaluators(serviceResourceTrie, resource.getAsMap(), request.getResourceElementMatchingScopes(), predicate);

				if (LOG.isDebugEnabled()) {
					LOG.debug("Found [" + ret.size() + "] service-resource-matchers for service-resource [" + resource.getAsString() + "]");
				}

				if (predicate != null) {
					cache.cacheEvaluators(resource.getCacheKey(), request.getResourceElementMatchingScopes(), ret);
				}
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Found [" + ret.size() + "] service-resource-matchers for service-resource [" + resource.getAsString() + "] in the cache");
				}
			}

			RangerPerfTracer.logAlways(perf);
		}
		if (ret == null) {
			ret = new ArrayList<>();
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerTagEnricher.getEvaluators(request=" + request + "): evaluators=" + ret);
		}

		return ret;
	}

	private boolean excludeDescendantMatches(RangerAccessRequest request) {
		final boolean ret;

		if (request.isAccessTypeAny() || RangerAccessRequestUtil.getIsAnyAccessInContext(request.getContext())) {
			ret = false;
		} else {
			RangerAccessResource resource = request.getResource();
			String               leafName = resource.getLeafName();

			if (StringUtils.isNotEmpty(leafName)) {
				RangerServiceDefHelper       helper      = new RangerServiceDefHelper(getServiceDef());
				Set<List<RangerResourceDef>> hierarchies = helper.getResourceHierarchies(RangerPolicy.POLICY_TYPE_ACCESS, resource.getKeys());

				// skip caching if the leaf of accessed resource is the deepest in the only applicable hierarchy
				if (hierarchies.size() == 1) {
					List<RangerResourceDef> theHierarchy    = hierarchies.iterator().next();
					RangerResourceDef       leafOfHierarchy = theHierarchy.get(theHierarchy.size() - 1);

					if (StringUtils.equals(leafOfHierarchy.getName(), leafName)) {
						ret = false;
					} else {
						ret = true;
					}
				} else {
					ret = true;
				}
			} else {
				ret = false;
			}
		}
		return ret;
	}

	private static Set<RangerTagForEval> getTagsForServiceResource(Date accessTime, final ServiceTags serviceTags, final RangerServiceResource serviceResource, final RangerPolicyResourceMatcher.MatchType matchType) {
		Set<RangerTagForEval> ret = new HashSet<>();

		final Long resourceId                        = serviceResource.getId();
		final Map<Long, List<Long>> resourceToTagIds = serviceTags.getResourceToTagIds();
		final Map<Long, RangerTag> tags              = serviceTags.getTags();

		if (LOG.isDebugEnabled()) {
			LOG.debug("Looking for tags for resource-id:[" + resourceId + "] in serviceTags:[" + serviceTags + "]");
		}

		if (resourceId != null && MapUtils.isNotEmpty(resourceToTagIds) && MapUtils.isNotEmpty(tags)) {

			List<Long> tagIds = resourceToTagIds.get(resourceId);

			if (CollectionUtils.isNotEmpty(tagIds)) {

				accessTime = accessTime == null ? new Date() : accessTime;

				for (Long tagId : tagIds) {

					RangerTag tag = tags.get(tagId);

					if (tag != null) {
						RangerTagForEval tagForEval = new RangerTagForEval(tag, matchType);
						if (tagForEval.isApplicable(accessTime)) {
							ret.add(tagForEval);
						}
					}
				}
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("No tags mapping found for resource:[" + resourceId + "]");
				}
			}
		} else {
			if (LOG.isDebugEnabled()) {
				LOG.debug("resourceId is null or resourceToTagTds mapping is null or tags mapping is null!");
			}
		}

		return ret;
	}

	private Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> copyServiceResourceTrie() {
		Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> ret = new HashMap<>();

		if (enrichedServiceTags != null) {
			for (Map.Entry<String, RangerResourceTrie<RangerServiceResourceMatcher>> entry : enrichedServiceTags.getServiceResourceTrie().entrySet()) {
				RangerResourceTrie<RangerServiceResourceMatcher> resourceTrie = new RangerResourceTrie<>(entry.getValue());
				ret.put(entry.getKey(), resourceTrie);
			}
		}
		return ret;
	}

	static public final class EnrichedServiceTags {
		final private ServiceTags                                                      serviceTags;
		final private List<RangerServiceResourceMatcher>                               serviceResourceMatchers;
		final private Map<String, RangerResourceTrie<RangerServiceResourceMatcher>>    serviceResourceTrie;
		final private Set<RangerTagForEval>                                            tagsForEmptyResourceAndAnyAccess; // Used only when accessed resource is empty and access type is 'any'
		final private Long                                                             resourceTrieVersion;

		EnrichedServiceTags(ServiceTags serviceTags, List<RangerServiceResourceMatcher> serviceResourceMatchers, Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> serviceResourceTrie) {
			this.serviceTags                      = serviceTags;
			this.serviceResourceMatchers          = serviceResourceMatchers;
			this.serviceResourceTrie              = serviceResourceTrie;
			this.tagsForEmptyResourceAndAnyAccess = createTagsForEmptyResourceAndAnyAccess();
			this.resourceTrieVersion              = serviceTags.getTagVersion();
		}
		public ServiceTags                                                   getServiceTags() {return serviceTags;}
		public List<RangerServiceResourceMatcher>                            getServiceResourceMatchers() { return serviceResourceMatchers;}
		public Map<String, RangerResourceTrie<RangerServiceResourceMatcher>> getServiceResourceTrie() { return serviceResourceTrie;}
		public Long                                                          getResourceTrieVersion() { return resourceTrieVersion;}
		public Set<RangerTagForEval>                                         getTagsForEmptyResourceAndAnyAccess() { return tagsForEmptyResourceAndAnyAccess;}

		private Set<RangerTagForEval> createTagsForEmptyResourceAndAnyAccess() {
			Set<RangerTagForEval> tagsForEmptyResourceAndAnyAccess = new HashSet<>();
			for (Map.Entry<Long, RangerTag> entry : serviceTags.getTags().entrySet()) {
				tagsForEmptyResourceAndAnyAccess.add(new RangerTagForEval(entry.getValue(), RangerPolicyResourceMatcher.MatchType.DESCENDANT));
			}
			return tagsForEmptyResourceAndAnyAccess;
		}
	}

	static class RangerTagRefresher extends Thread {
		private static final Logger LOG = LoggerFactory.getLogger(RangerTagRefresher.class);

		private final RangerTagRetriever tagRetriever;
		private final RangerTagEnricher tagEnricher;
		private long lastKnownVersion;
		private final BlockingQueue<DownloadTrigger> tagDownloadQueue;
		private long lastActivationTimeInMillis;

		private final String cacheFile;
		private boolean      hasProvidedTagsToReceiver;

		RangerTagRefresher(RangerTagRetriever tagRetriever, RangerTagEnricher tagEnricher, long lastKnownVersion, BlockingQueue<DownloadTrigger> tagDownloadQueue, String cacheFile) {
			this.tagRetriever = tagRetriever;
			this.tagEnricher = tagEnricher;
			this.lastKnownVersion = lastKnownVersion;
			this.tagDownloadQueue = tagDownloadQueue;
			this.cacheFile = cacheFile;
			setName("RangerTagRefresher(serviceName=" + tagRetriever.getServiceName() + ")-" + getId());
		}

		public long getLastActivationTimeInMillis() {
			return lastActivationTimeInMillis;
		}

		public void setLastActivationTimeInMillis(long lastActivationTimeInMillis) {
			this.lastActivationTimeInMillis = lastActivationTimeInMillis;
		}

		@Override
		public void run() {

			if (LOG.isDebugEnabled()) {
				LOG.debug("==> RangerTagRefresher().run()");
			}

			while (true) {
				DownloadTrigger trigger = null;

				try {
					RangerPerfTracer perf = null;

					if(RangerPerfTracer.isPerfTraceEnabled(PERF_CONTEXTENRICHER_INIT_LOG)) {
						perf = RangerPerfTracer.getPerfTracer(PERF_CONTEXTENRICHER_INIT_LOG, "RangerTagRefresher(" + getName() + ").populateTags(lastKnownVersion=" + lastKnownVersion + ")");
					}
					trigger = tagDownloadQueue.take();
					populateTags();

					RangerPerfTracer.log(perf);

				} catch (InterruptedException excp) {
					LOG.info("RangerTagRefresher(" + getName() + ").run(): Interrupted! Exiting thread", excp);
					break;
				} finally {
					if (trigger != null) {
						trigger.signalCompletion();
					}
				}
			}

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerTagRefresher().run()");
			}
		}

		private void populateTags() throws InterruptedException {

			if (tagEnricher != null) {
				ServiceTags serviceTags;

				try {
					serviceTags = tagRetriever.retrieveTags(lastKnownVersion, lastActivationTimeInMillis);

					if (serviceTags == null) {
						if (!hasProvidedTagsToReceiver) {
							serviceTags = loadFromCache();
						}
					} else if (!serviceTags.getIsDelta()) {
						saveToCache(serviceTags);
					}

					if (serviceTags != null) {
						tagEnricher.setServiceTags(serviceTags);
						if (serviceTags.getIsDelta() && serviceTags.getTagVersion() != -1L) {
							saveToCache(tagEnricher.enrichedServiceTags.serviceTags);
						}
						LOG.info("RangerTagRefresher(serviceName=" + tagRetriever.getServiceName() + ").populateTags() - Updated tags-cache to new version of tags, lastKnownVersion=" + lastKnownVersion + "; newVersion="
								+ (serviceTags.getTagVersion() == null ? -1L : serviceTags.getTagVersion()));
						hasProvidedTagsToReceiver = true;
						lastKnownVersion = serviceTags.getTagVersion() == null ? -1L : serviceTags.getTagVersion();
						setLastActivationTimeInMillis(System.currentTimeMillis());
					} else {
						if (LOG.isDebugEnabled()) {
							LOG.debug("RangerTagRefresher(serviceName=" + tagRetriever.getServiceName() + ").populateTags() - No need to update tags-cache. lastKnownVersion=" + lastKnownVersion);
						}
					}
				} catch (RangerServiceNotFoundException snfe) {
					LOG.error("Caught ServiceNotFound exception :", snfe);

					// Need to clean up local tag cache
					if (tagEnricher.disableCacheIfServiceNotFound) {
						disableCache();
						tagEnricher.setServiceTags(null);
						setLastActivationTimeInMillis(System.currentTimeMillis());
						lastKnownVersion = -1L;
					}
				} catch (InterruptedException interruptedException) {
					throw interruptedException;
				} catch (Exception e) {
					LOG.error("RangerTagRefresher(serviceName=" + tagRetriever.getServiceName() + ").populateTags(): Encountered unexpected exception. Ignoring", e);
				}

			} else {
				LOG.error("RangerTagRefresher(serviceName=" + tagRetriever.getServiceName() + ".populateTags() - no tag receiver to update tag-cache");
			}
		}

		void cleanup() {
			if (LOG.isDebugEnabled()) {
				LOG.debug("==> RangerTagRefresher.cleanup()");
			}

			stopRefresher();

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerTagRefresher.cleanup()");
			}
		}

		final void startRefresher() {
			try {
				super.start();
			} catch (Exception excp) {
				LOG.error("RangerTagRefresher(" + getName() + ").startRetriever(): Failed to start, exception=" + excp);
			}
		}

		private void stopRefresher() {

			if (super.isAlive()) {
				super.interrupt();

				boolean setInterrupted = false;
				boolean isJoined = false;

				while (!isJoined) {
					try {
						super.join();
						isJoined = true;
						if (LOG.isDebugEnabled()) {
							LOG.debug("RangerTagRefresher(" + getName() + ") is stopped");
						}
					} catch (InterruptedException excp) {
						LOG.warn("RangerTagRefresher(" + getName() + ").stopRefresher(): Error while waiting for thread to exit", excp);
						LOG.warn("Retrying Thread.join(). Current thread will be marked as 'interrupted' after Thread.join() returns");
						setInterrupted = true;
					}
				}
				if (setInterrupted) {
					Thread.currentThread().interrupt();
				}
			}
		}


		final ServiceTags loadFromCache() {
			ServiceTags serviceTags = null;

			if (LOG.isDebugEnabled()) {
				LOG.debug("==> RangerTagRetriever(serviceName=" + tagEnricher.getServiceName() + ").loadFromCache()");
			}

			File cacheFile = StringUtils.isEmpty(this.cacheFile) ? null : new File(this.cacheFile);

			if (cacheFile != null && cacheFile.isFile() && cacheFile.canRead()) {
				Reader reader = null;

				try {
					reader = new FileReader(cacheFile);

						serviceTags = JsonUtils.jsonToObject(reader, ServiceTags.class);

					if (serviceTags != null && !StringUtils.equals(tagEnricher.getServiceName(), serviceTags.getServiceName())) {
						LOG.warn("ignoring unexpected serviceName '" + serviceTags.getServiceName() + "' in cache file '" + cacheFile.getAbsolutePath() + "'");

						serviceTags.setServiceName(tagEnricher.getServiceName());
					}
				} catch (Exception excp) {
					LOG.error("failed to load service-tags from cache file " + cacheFile.getAbsolutePath(), excp);
				} finally {
					if (reader != null) {
						try {
							reader.close();
						} catch (Exception excp) {
							LOG.error("error while closing opened cache file " + cacheFile.getAbsolutePath(), excp);
						}
					}
				}
			} else {
				LOG.warn("cache file does not exist or not readable '" + (cacheFile == null ? null : cacheFile.getAbsolutePath()) + "'");
			}

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerTagRetriever(serviceName=" + tagEnricher.getServiceName() + ").loadFromCache()");
			}

			return serviceTags;
		}

		final void saveToCache(ServiceTags serviceTags) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("==> RangerTagRetriever(serviceName=" + tagEnricher.getServiceName() + ").saveToCache()");
			}

			if (serviceTags != null) {
				File cacheFile = StringUtils.isEmpty(this.cacheFile) ? null : new File(this.cacheFile);

				if (cacheFile != null) {
					Writer writer = null;

					try {
						writer = new FileWriter(cacheFile);

						JsonUtils.objectToWriter(writer, serviceTags);
					} catch (Exception excp) {
						LOG.error("failed to save service-tags to cache file '" + cacheFile.getAbsolutePath() + "'", excp);
					} finally {
						if (writer != null) {
							try {
								writer.close();
							} catch (Exception excp) {
								LOG.error("error while closing opened cache file '" + cacheFile.getAbsolutePath() + "'", excp);
							}
						}
					}
				}
			} else {
				LOG.info("service-tags is null for service=" + tagRetriever.getServiceName() + ". Nothing to save in cache");
			}

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerTagRetriever(serviceName=" + tagEnricher.getServiceName() + ").saveToCache()");
			}
		}

		final void disableCache() {
			if (LOG.isDebugEnabled()) {
				LOG.debug("==> RangerTagRetriever.disableCache(serviceName=" + tagEnricher.getServiceName() + ")");
			}

			File cacheFile = StringUtils.isEmpty(this.cacheFile) ? null : new File(this.cacheFile);
			if (cacheFile != null && cacheFile.isFile() && cacheFile.canRead()) {
				LOG.warn("Cleaning up local tags cache");
				String renamedCacheFile = cacheFile.getAbsolutePath() + "_" + System.currentTimeMillis();
				if (!cacheFile.renameTo(new File(renamedCacheFile))) {
					LOG.error("Failed to move " + cacheFile.getAbsolutePath() + " to " + renamedCacheFile);
				} else {
					LOG.warn("moved " + cacheFile.getAbsolutePath() + " to " + renamedCacheFile);
				}
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("No local TAGS cache found. No need to disable it!");
				}
			}

			if (LOG.isDebugEnabled()) {
				LOG.debug("<== RangerTagRetriever.disableCache(serviceName=" + tagEnricher.getServiceName() + ")");
			}
		}
	}

	private static class SelfOrAncestorPredicate implements Predicate {
		private final RangerServiceDef.RangerResourceDef leafResourceDef;

		public SelfOrAncestorPredicate(RangerServiceDef.RangerResourceDef leafResourceDef) {
			this.leafResourceDef = leafResourceDef;
		}

		@Override
		public boolean evaluate(Object o) {
			if (o instanceof RangerResourceEvaluator) {
				RangerResourceEvaluator evaluator = (RangerResourceEvaluator) o;

				return evaluator.isLeaf(leafResourceDef.getName()) || evaluator.isAncestorOf(leafResourceDef);
			}

			return false;
		}
	}
}
