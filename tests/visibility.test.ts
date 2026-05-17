import { describe, expect, it } from "vitest";
import { stripHiddenHtml } from "../packages/core/src/content/link-preview/content/visibility.js";

describe("hidden content stripping", () => {
  it("removes common hidden element patterns", () => {
    const html = `
      <html><body>
        <p>Visible</p>
        <div style="display:none">Hidden display</div>
        <div style="display:none/*inline comment*/">Hidden display comment</div>
        <div style="display:none;display:">Hidden display malformed override</div>
        <div style="display:none!important">Hidden display important</div>
        <div style="display:none!important; display:block">Hidden display important cascade</div>
        <div style="visibility: hidden">Hidden visibility</div>
        <div style="visibility:hidden/*inline comment*/">Hidden visibility comment</div>
        <div style="visibility:hidden!important">Hidden visibility important</div>
        <div style="visibility:hidden!important; visibility:visible">Hidden visibility important cascade</div>
        <div style="opacity:0">Hidden opacity</div>
        <div style="opacity:0; opacity:">Hidden opacity malformed override</div>
        <div style="font-size:0">Hidden font</div>
        <div style="clip: rect(0,0,0,0)">Hidden clip</div>
        <div style="clip-path: inset(100%)">Hidden clip-path</div>
        <div style="text-indent:-9999px">Hidden indent</div>
        <div style="transform: scale(0)">Hidden scale</div>
        <div style="position:absolute; left:-9999px">Hidden left</div>
        <div style="position:absolute!important; left:-9999px">Hidden important left</div>
        <div style="position:fixed; top:-9999px">Hidden top</div>
        <div style="width:0; height:0; overflow:hidden">Hidden size</div>
        <div hidden>Hidden attribute</div>
        <div aria-hidden="true">Hidden aria</div>
        <input type="hidden" value="Hidden input" />
        <template>Hidden template</template>
        <script>Hidden script</script>
        <!-- Hidden comment -->
      </body></html>
    `;

    const cleaned = stripHiddenHtml(html);
    expect(cleaned).toContain("Visible");
    expect(cleaned).not.toContain("Hidden display");
    expect(cleaned).not.toContain("Hidden display comment");
    expect(cleaned).not.toContain("Hidden display malformed override");
    expect(cleaned).not.toContain("Hidden display important");
    expect(cleaned).not.toContain("Hidden display important cascade");
    expect(cleaned).not.toContain("Hidden visibility");
    expect(cleaned).not.toContain("Hidden visibility comment");
    expect(cleaned).not.toContain("Hidden visibility important");
    expect(cleaned).not.toContain("Hidden visibility important cascade");
    expect(cleaned).not.toContain("Hidden opacity");
    expect(cleaned).not.toContain("Hidden opacity malformed override");
    expect(cleaned).not.toContain("Hidden font");
    expect(cleaned).not.toContain("Hidden clip");
    expect(cleaned).not.toContain("Hidden clip-path");
    expect(cleaned).not.toContain("Hidden indent");
    expect(cleaned).not.toContain("Hidden scale");
    expect(cleaned).not.toContain("Hidden left");
    expect(cleaned).not.toContain("Hidden important left");
    expect(cleaned).not.toContain("Hidden top");
    expect(cleaned).not.toContain("Hidden size");
    expect(cleaned).not.toContain("Hidden attribute");
    expect(cleaned).not.toContain("Hidden aria");
    expect(cleaned).not.toContain("Hidden input");
    expect(cleaned).not.toContain("Hidden template");
    expect(cleaned).not.toContain("Hidden script");
    expect(cleaned).not.toContain("Hidden comment");
  });

  it("keeps visible content without hidden markers", () => {
    const html = `
      <html><body>
        <div style="opacity:0.5">Visible alpha</div>
        <div style="position:absolute; left:10px">Visible positioned</div>
        <div style="font-size:0.875rem">Visible small text</div>
        <div style="backface-visibility:hidden">Visible transformed text</div>
      </body></html>
    `;

    const cleaned = stripHiddenHtml(html);
    expect(cleaned).toContain("Visible alpha");
    expect(cleaned).toContain("Visible positioned");
    expect(cleaned).toContain("Visible small text");
    expect(cleaned).toContain("Visible transformed text");
  });
});
