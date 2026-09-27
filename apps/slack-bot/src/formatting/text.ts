/**
 * Shortens text to at most `max` characters, marking the cut. Slack refuses a whole message
 * when one field is too long (150 for a header, 3,000 for a section), so values that come from
 * outside -- alert annotations, pipeline names -- are clipped before they reach a block.
 */
export function clip(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}
