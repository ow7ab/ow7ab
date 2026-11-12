import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';
import { initializeFirestore } from 'firebase/firestore';

/**
 * Reuses the existing PMP PAINTS Firebase project and multi-tenant Firestore database.
 * No new Firebase project or separate database is created.
 */
export const firebaseConfig = {
  apiKey: "AIzaSyDalLI3Bg-vR0NXaw69a6nyx4IxBw2dINo",
  authDomain: "gen-lang-client-0220128746.firebaseapp.com",
  projectId: "gen-lang-client-0220128746",
  storageBucket: "gen-lang-client-0220128746.firebasestorage.app",
  messagingSenderId: "675531579375",
  appId: "1:675531579375:web:pmp-paints-erp"
};

export const FIRESTORE_DATABASE_ID = "ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248";
export const DEFAULT_CLUSTER = "PMP-ERP-MAIN-CLUSTER";

const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApp();

export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();

export const firestore = initializeFirestore(app, {}, FIRESTORE_DATABASE_ID);

export default app;
