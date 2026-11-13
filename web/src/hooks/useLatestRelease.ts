import { useState, useEffect, useCallback } from 'react';
import { fetchReleaseFeed, ReleaseFeedResult } from '../services/ReleaseService';

let cachedFeed: ReleaseFeedResult | null = null;
let inflightPromise: Promise<ReleaseFeedResult> | null = null;

async function loadSharedReleaseFeed(force = false): Promise<ReleaseFeedResult> {
  if (!force && cachedFeed) return cachedFeed;
  if (!force && inflightPromise) return inflightPromise;

  inflightPromise = fetchReleaseFeed()
    .then((res) => {
      cachedFeed = res;
      return res;
    })
    .finally(() => {
      inflightPromise = null;
    });

  return inflightPromise;
}

export function useLatestRelease() {
  const [feed, setFeed] = useState<ReleaseFeedResult | null>(cachedFeed);
  const [isLoading, setIsLoading] = useState<boolean>(!cachedFeed);

  const refresh = useCallback(async (force = true) => {
    setIsLoading(true);
    try {
      const next = await loadSharedReleaseFeed(force);
      setFeed(next);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    let mounted = true;
    loadSharedReleaseFeed(false)
      .then((res) => {
        if (mounted) {
          setFeed(res);
          setIsLoading(false);
        }
      })
      .catch(() => {
        if (mounted) setIsLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, []);

  return {
    feed,
    latestRelease: feed?.latest ?? null,
    releaseHistory: feed?.history ?? [],
    apkAvailable: feed?.apkAvailable ?? false,
    unavailableReason: feed?.unavailableReason ?? null,
    isLoading,
    refresh
  };
}
