/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        slate: {
          750: '#1e2a3a',
          850: '#0f1929',
          950: '#060d1a',
        },
      },
    },
  },
  plugins: [],
}
