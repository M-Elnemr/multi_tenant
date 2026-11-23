export default function Loading() {
  return (
    <div className="o-container pt-5" aria-busy="true">
      <div className="o-skel h-56 md:h-80" style={{ borderRadius: 26 }} />
      <div className="mt-6 flex gap-4 overflow-hidden">{Array.from({ length: 8 }).map((_, i) => <div key={i} className="o-skel h-24 w-24 shrink-0 md:h-28 md:w-28" style={{ borderRadius: 28 }} />)}</div>
      <div className="mt-8 grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-5">{Array.from({ length: 10 }).map((_, i) => <div key={i} className="o-skel aspect-[4/5]" />)}</div>
    </div>
  );
}
