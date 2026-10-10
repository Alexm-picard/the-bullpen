import { useEffect, useState, type RefObject } from "react";

/** True once `ref` has scrolled ABOVE the viewport top (minus `offsetPx` for the fixed header). */
export function useScrolledPast(
  ref: RefObject<Element | null>,
  offsetPx: number,
): boolean {
  const [past, setPast] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (el == null || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (!entry) return;
        setPast(!entry.isIntersecting && entry.boundingClientRect.top < 0);
      },
      { rootMargin: `-${offsetPx}px 0px 0px 0px`, threshold: 0 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [ref, offsetPx]);
  return past;
}
