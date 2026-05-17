export function SchemeChips({ scheme }: { scheme: string }) {
  return (
    <span className={`scheme-chips scheme-${scheme}`} aria-hidden="true">
      <span></span>
      <span></span>
      <span></span>
      <span></span>
    </span>
  );
}
