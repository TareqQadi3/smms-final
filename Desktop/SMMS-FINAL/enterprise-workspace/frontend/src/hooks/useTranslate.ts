import { useState, useEffect, useRef } from 'react';

/**
 * Auto-translate hook using MyMemory free API.
 * Debounce 700ms — fires only after user stops typing.
 *
 * @param text   - The source text to translate
 * @param from   - Source language: 'ar' or 'en'
 * @returns { translated, translating }
 */
export function useTranslate(text: string, from: 'ar' | 'en') {
  const to = from === 'ar' ? 'en' : 'ar';
  const [translated,  setTranslated]  = useState('');
  const [translating, setTranslating] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!text || text.trim().length < 2) {
      setTranslated('');
      return;
    }

    if (timerRef.current) clearTimeout(timerRef.current);

    timerRef.current = setTimeout(async () => {
      setTranslating(true);
      try {
        const url = `https://api.mymemory.translated.net/get?q=${encodeURIComponent(text.trim())}&langpair=${from}|${to}`;
        const res  = await fetch(url);
        const data = await res.json();
        if (data?.responseStatus === 200 && data?.responseData?.translatedText) {
          // MyMemory sometimes returns HTML-encoded text — decode it
          const raw = data.responseData.translatedText as string;
          setTranslated(raw.replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n))));
        }
      } catch {
        /* silent — translation is a helper, not critical */
      } finally {
        setTranslating(false);
      }
    }, 700);

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [text, from, to]);

  return { translated, translating };
}
