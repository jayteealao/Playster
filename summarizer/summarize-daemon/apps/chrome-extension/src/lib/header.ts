export function buildIdleSubtitle({
  inputSummary,
  modelLabel,
  model,
}: {
  inputSummary?: string | null;
  modelLabel?: string | null;
  model?: string | null;
}): string {
  const input = typeof inputSummary === "string" ? inputSummary.trim() : "";
  void modelLabel;
  void model;
  return input;
}
