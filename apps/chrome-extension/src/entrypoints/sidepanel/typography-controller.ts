export function createTypographyController({
  sizeSmBtn,
  sizeLgBtn,
  lineTightBtn,
  lineLooseBtn,
  defaultFontSize,
  defaultLineHeight,
}: {
  sizeSmBtn: HTMLButtonElement;
  sizeLgBtn: HTMLButtonElement;
  lineTightBtn: HTMLButtonElement;
  lineLooseBtn: HTMLButtonElement;
  defaultFontSize: number;
  defaultLineHeight: number;
}) {
  const MIN_FONT_SIZE = 12;
  const MAX_FONT_SIZE = 20;
  const MIN_LINE_HEIGHT = 1.2;
  const MAX_LINE_HEIGHT = 1.9;

  let currentFontSize = defaultFontSize;
  let currentLineHeight = defaultLineHeight;

  const updateSizeControls = () => {
    sizeSmBtn.disabled = currentFontSize <= MIN_FONT_SIZE;
    sizeLgBtn.disabled = currentFontSize >= MAX_FONT_SIZE;
  };

  const updateLineHeightControls = () => {
    lineTightBtn.disabled = currentLineHeight <= MIN_LINE_HEIGHT;
    lineLooseBtn.disabled = currentLineHeight >= MAX_LINE_HEIGHT;
  };

  const clampFontSize = (value: number) => {
    return Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, Math.round(value)));
  };

  const clampLineHeight = (value: number) => {
    const rounded = Math.round(value * 10) / 10;
    return Math.min(MAX_LINE_HEIGHT, Math.max(MIN_LINE_HEIGHT, rounded));
  };

  return {
    apply(fontFamily: string, fontSize: number, lineHeight: number) {
      document.documentElement.style.setProperty("--font-body", fontFamily);
      document.documentElement.style.setProperty("--font-size", `${fontSize}px`);
      document.documentElement.style.setProperty("--line-height", `${lineHeight}`);
    },
    clampFontSize,
    clampLineHeight,
    getCurrentFontSize() {
      return currentFontSize;
    },
    getCurrentLineHeight() {
      return currentLineHeight;
    },
    setCurrentFontSize(value: number) {
      currentFontSize = clampFontSize(value);
      updateSizeControls();
    },
    setCurrentLineHeight(value: number) {
      currentLineHeight = clampLineHeight(value);
      updateLineHeightControls();
    },
  };
}
