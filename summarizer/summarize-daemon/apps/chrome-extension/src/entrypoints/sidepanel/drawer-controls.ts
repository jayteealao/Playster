type DrawerRuntimeOpts = {
  drawerEl: HTMLElement;
  drawerToggleBtn: HTMLButtonElement;
  advancedSettingsEl: HTMLDetailsElement;
  advancedSettingsBodyEl: HTMLElement;
  refreshModelsIfStale: () => void;
};

export function createDrawerControls(opts: DrawerRuntimeOpts) {
  let drawerAnimation: Animation | null = null;
  let advancedSettingsAnimation: Animation | null = null;

  function toggleDrawer(force?: boolean, animationOpts?: { animate?: boolean }) {
    const reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ?? false;
    const animate = animationOpts?.animate !== false && !reducedMotion;

    const isOpen = !opts.drawerEl.classList.contains("hidden");
    const next = typeof force === "boolean" ? force : !isOpen;

    opts.drawerToggleBtn.classList.toggle("isActive", next);
    opts.drawerToggleBtn.setAttribute("aria-expanded", next ? "true" : "false");
    opts.drawerEl.setAttribute("aria-hidden", next ? "false" : "true");

    if (next === isOpen) return;

    const cleanup = () => {
      opts.drawerEl.style.removeProperty("height");
      opts.drawerEl.style.removeProperty("opacity");
      opts.drawerEl.style.removeProperty("transform");
      opts.drawerEl.style.removeProperty("overflow");
    };

    drawerAnimation?.cancel();
    drawerAnimation = null;
    cleanup();

    if (!animate) {
      opts.drawerEl.classList.toggle("hidden", !next);
      return;
    }

    if (next) {
      opts.drawerEl.classList.remove("hidden");
      const targetHeight = opts.drawerEl.scrollHeight;
      opts.drawerEl.style.height = "0px";
      opts.drawerEl.style.opacity = "0";
      opts.drawerEl.style.transform = "translateY(-6px)";
      opts.drawerEl.style.overflow = "hidden";

      drawerAnimation = opts.drawerEl.animate(
        [
          { height: "0px", opacity: 0, transform: "translateY(-6px)" },
          { height: `${targetHeight}px`, opacity: 1, transform: "translateY(0px)" },
        ],
        { duration: 200, easing: "cubic-bezier(0.2, 0, 0, 1)" },
      );
      drawerAnimation.onfinish = () => {
        drawerAnimation = null;
        cleanup();
      };
      drawerAnimation.oncancel = () => {
        drawerAnimation = null;
      };
      return;
    }

    const currentHeight = opts.drawerEl.getBoundingClientRect().height;
    opts.drawerEl.style.height = `${currentHeight}px`;
    opts.drawerEl.style.opacity = "1";
    opts.drawerEl.style.transform = "translateY(0px)";
    opts.drawerEl.style.overflow = "hidden";

    drawerAnimation = opts.drawerEl.animate(
      [
        { height: `${currentHeight}px`, opacity: 1, transform: "translateY(0px)" },
        { height: "0px", opacity: 0, transform: "translateY(-6px)" },
      ],
      { duration: 180, easing: "cubic-bezier(0.4, 0, 0.2, 1)" },
    );
    drawerAnimation.onfinish = () => {
      drawerAnimation = null;
      opts.drawerEl.classList.add("hidden");
      cleanup();
    };
    drawerAnimation.oncancel = () => {
      drawerAnimation = null;
    };
  }

  function toggleAdvancedSettings(force?: boolean, animationOpts?: { animate?: boolean }) {
    const reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ?? false;
    const animate = animationOpts?.animate !== false && !reducedMotion;
    const isOpen = opts.advancedSettingsEl.open;
    const next = typeof force === "boolean" ? force : !isOpen;

    if (next === isOpen) {
      if (next) opts.refreshModelsIfStale();
      return;
    }

    const cleanup = () => {
      opts.advancedSettingsBodyEl.style.removeProperty("height");
      opts.advancedSettingsBodyEl.style.removeProperty("opacity");
      opts.advancedSettingsBodyEl.style.removeProperty("transform");
      opts.advancedSettingsBodyEl.style.removeProperty("overflow");
    };

    advancedSettingsAnimation?.cancel();
    advancedSettingsAnimation = null;
    cleanup();

    if (!animate) {
      opts.advancedSettingsEl.open = next;
      if (next) opts.refreshModelsIfStale();
      return;
    }

    if (next) {
      opts.advancedSettingsBodyEl.style.height = "0px";
      opts.advancedSettingsBodyEl.style.opacity = "0";
      opts.advancedSettingsBodyEl.style.transform = "translateY(-6px)";
      opts.advancedSettingsBodyEl.style.overflow = "hidden";
      opts.advancedSettingsEl.open = true;

      const targetHeight = opts.advancedSettingsBodyEl.scrollHeight;
      advancedSettingsAnimation = opts.advancedSettingsBodyEl.animate(
        [
          { height: "0px", opacity: 0, transform: "translateY(-6px)" },
          { height: `${targetHeight}px`, opacity: 1, transform: "translateY(0px)" },
        ],
        { duration: 200, easing: "cubic-bezier(0.2, 0, 0, 1)", fill: "both" },
      );
      advancedSettingsAnimation.onfinish = () => {
        advancedSettingsAnimation = null;
        cleanup();
        opts.refreshModelsIfStale();
      };
      advancedSettingsAnimation.oncancel = () => {
        advancedSettingsAnimation = null;
      };
      return;
    }

    const currentHeight = opts.advancedSettingsBodyEl.getBoundingClientRect().height;
    opts.advancedSettingsBodyEl.style.height = `${currentHeight}px`;
    opts.advancedSettingsBodyEl.style.opacity = "1";
    opts.advancedSettingsBodyEl.style.transform = "translateY(0px)";
    opts.advancedSettingsBodyEl.style.overflow = "hidden";

    advancedSettingsAnimation = opts.advancedSettingsBodyEl.animate(
      [
        { height: `${currentHeight}px`, opacity: 1, transform: "translateY(0px)" },
        { height: "0px", opacity: 0, transform: "translateY(-6px)" },
      ],
      { duration: 180, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" },
    );
    advancedSettingsAnimation.onfinish = () => {
      advancedSettingsAnimation = null;
      opts.advancedSettingsEl.open = false;
      cleanup();
    };
    advancedSettingsAnimation.oncancel = () => {
      advancedSettingsAnimation = null;
    };
  }

  return {
    hasAdvancedSettingsAnimation: () => Boolean(advancedSettingsAnimation),
    toggleAdvancedSettings,
    toggleDrawer,
  };
}
