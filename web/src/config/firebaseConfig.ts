import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';

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

const app = !getApps().length ? initializeApp(firebaseConfig) : getApp();
export const auth = getAuth(app);
export const firestore = getFirestore(app, DEFAULT_DATABASE_ID);
export const googleAuthProvider = new GoogleAuthProvider();

export default app;
