/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: ['class', '[data-theme="true-black"]'],
  theme: {
    extend: {
      colors: {
        'pmp-primary': '#DC2626',
        'pmp-red': '#DC2626',
        'pmp-gold': '#F59E0B',
        'pmp-cyan': '#06B6D4',
        'pmp-green': '#10B981',
        'pmp-warning': '#F59E0B',
        'pmp-success': '#10B981',
        'theme-base': 'var(--bg-primary)',
        'theme-surface': 'var(--surface-primary)',
        'theme-elevated': 'var(--surface-elevated)',
        'theme-primary': 'var(--text-primary)',
        'theme-secondary': 'var(--text-secondary)',
        'theme-muted': 'var(--text-muted)',
      },
      fontFamily: {
        sans: ['Cairo', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
      },
      borderColor: {
        theme: 'var(--border-subtle)',
      }
    },
  },
  plugins: [],
};
