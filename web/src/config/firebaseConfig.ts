import { initializeApp, getApps, getApp, FirebaseApp } from 'firebase/app';
import { Auth, getAuth, GoogleAuthProvider } from 'firebase/auth';
import {
  Firestore,
  getFirestore,
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager
} from 'firebase/firestore';

/**
 * Reuses the EXACT existing Firebase Project and credentials from the Android app:
 * - Project ID: gen-lang-client-0220128746
 * - Storage Bucket: gen-lang-client-0220128746.firebasestorage.app
 * - Database ID: ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248
 * - Auth Web Client: 675531579375-732a74lc8b5r1ilu568geaigi1u6rb0i.apps.googleusercontent.com
 */
export const firebaseConfig = {
  apiKey: "AIzaSyDalLI3Bg-vR0NXaw69a6nyx4IxBw2dINo",
  authDomain: "gen-lang-client-0220128746.firebaseapp.com",
  projectId: "gen-lang-client-0220128746",
  storageBucket: "gen-lang-client-0220128746.firebasestorage.app",
  messagingSenderId: "675531579375",
  appId: "1:675531579375:web:pmp-paints-portal-web"
};

export const DEFAULT_DATABASE_ID = "ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248";
export const DEFAULT_CLUSTER_CODE = "PMP-ERP-MAIN-CLUSTER";

const app: FirebaseApp = !getApps().length ? initializeApp(firebaseConfig) : getApp();
export const auth: Auth = getAuth(app);

let resolvedFirestore: Firestore;
try {
  // Enable persistent multi-tab IndexedDB offline persistence across Web & Electron Desktop
  resolvedFirestore = initializeFirestore(
    app,
    {
      localCache: persistentLocalCache({
        tabManager: persistentMultipleTabManager()
      })
    },
    DEFAULT_DATABASE_ID
  );
} catch {
  // Fallback when Firestore is already initialized during HMR or restricted environments
  resolvedFirestore = getFirestore(app, DEFAULT_DATABASE_ID);
}

export const firestore: Firestore = resolvedFirestore;
export const googleAuthProvider = new GoogleAuthProvider();

/**
 * Secondary isolated Firebase Auth instance used ONLY by Admin user provisioning
 * and pre-flight credential verification during account switching, so the active
 * primary workstation Firebase session is never unexpectedly displaced.
 */
export function getProvisioningAuth(): Auth {
  const existing = getApps().find((a) => a.name === 'pmp-auth-provisioner');
  const provisionApp = existing || initializeApp(firebaseConfig, 'pmp-auth-provisioner');
  return getAuth(provisionApp);
}

export default app;
