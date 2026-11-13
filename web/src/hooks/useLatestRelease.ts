import { useEffect, useState, useCallback } from 'react';
import {
  DEFAULT_STABLE_RELEASE,
  fetchReleaseFeed,
  ReleaseFeedResult,
  StableApkRelease
} from '../services/ReleaseService';

let cachedFeed: ReleaseFeedResult | null = null;
let inflightPromise: Promise<ReleaseFeedResult> | null = null;

export interface UseLatestReleaseState {
  latestRelease: StableApkRelease | null;
  releasesHistory: StableApkRelease[];
  sourceLabel: string;
  apkAvailable: boolean;
  unavailableReason: string | null;
  isLoading: boolean;
  refreshReleases: () => Promise<void>;
}

const INITIAL_FEED: ReleaseFeedResult = {
  latest: DEFAULT_STABLE_RELEASE,
  history: [DEFAULT_STABLE_RELEASE],
  sourceLabel: DEFAULT_STABLE_RELEASE.sourceLabel,
  apkAvailable: true,
  unavailableReason: null
};

export function useLatestRelease(): UseLatestReleaseState {
  const [feed, setFeed] = useState<ReleaseFeedResult>(cachedFeed || INITIAL_FEED);
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const loadFeed = useCallback(async (force = false) => {
    if (!force && cachedFeed) {
      setFeed(cachedFeed);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    try {
      if (!force && inflightPromise) {
        const res = await inflightPromise;
        cachedFeed = res;
        setFeed(res);
      } else {
        inflightPromise = fetchReleaseFeed();
        const res = await inflightPromise;
        cachedFeed = res;
        setFeed(res);
      }
    } finally {
      inflightPromise = null;
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadFeed(false);
  }, [loadFeed]);

  const refreshReleases = useCallback(async () => {
    await loadFeed(true);
  }, [loadFeed]);

  return {
    latestRelease: feed.latest || DEFAULT_STABLE_RELEASE,
    releasesHistory: feed.history.length > 0 ? feed.history : [DEFAULT_STABLE_RELEASE],
    sourceLabel: feed.sourceLabel,
    apkAvailable: true,
    unavailableReason: null,
    isLoading,
    refreshReleases
  };
}
