import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

export type ThemeName = 'default' | 'corporate' | 'warm';

interface ThemeStore {
  theme: ThemeName;
  isDark: boolean;
  setTheme: (theme: ThemeName) => void;
  toggleDark: () => void;
}

export const useThemeStore = create<ThemeStore>()(
  persist(
    (set) => ({
      theme: 'default',
      isDark: false,

      setTheme: (theme) => set({ theme }),

      toggleDark: () => set((state) => ({ isDark: !state.isDark })),
    }),
    {
      name: 'theme-storage',
      // localStorage so the user's theme preference survives new tabs / browser restarts.
      storage: createJSONStorage(() => localStorage),
    }
  )
);
