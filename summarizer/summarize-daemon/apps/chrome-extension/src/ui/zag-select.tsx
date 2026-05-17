import { normalizeProps, useMachine } from "@zag-js/preact";
import * as select from "@zag-js/select";
import { useEffect, useMemo, useRef } from "preact/hooks";

const OPEN_EVENT = "summarize:select-open";

export type SelectItem = {
  label: string;
  value: string;
  disabled?: boolean;
};

type UseZagSelectArgs = {
  id: string;
  items: SelectItem[];
  value: string;
  onValueChange: (value: string) => void;
};

export function useZagSelect({ id, items, value, onValueChange }: UseZagSelectArgs) {
  const collection = useMemo(
    () =>
      select.collection({
        items,
        itemToValue: (item) => item.value,
        itemToString: (item) => item.label,
        isItemDisabled: (item) => Boolean(item.disabled),
      }),
    [items],
  );

  const syncing = useRef(false);

  const service = useMachine(select.machine, {
    id,
    collection,
    positioning: {
      placement: "bottom-start",
      gutter: 6,
      sameWidth: true,
      strategy: "fixed",
      fitViewport: true,
      flip: true,
      shift: 8,
      overflowPadding: 8,
    },
    defaultValue: value ? [value] : [],
    onValueChange: ({ value: next }: select.ValueChangeDetails) => {
      if (syncing.current) return;
      onValueChange(next[0] ?? "");
    },
  });

  const api = select.connect(service, normalizeProps);
  const apiRef = useRef(api);
  apiRef.current = api;

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ id?: string }>).detail;
      if (!detail || detail.id === id) return;
      apiRef.current.setOpen(false);
    };
    window.addEventListener(OPEN_EVENT, handler as EventListener);
    return () => {
      window.removeEventListener(OPEN_EVENT, handler as EventListener);
    };
  }, [id]);

  useEffect(() => {
    if (!api.open) return;
    window.dispatchEvent(new CustomEvent(OPEN_EVENT, { detail: { id } }));
  }, [api.open, id]);

  useEffect(() => {
    const nextValue = value ? [value] : [];
    const current = apiRef.current.value[0] ?? "";
    if (current === (nextValue[0] ?? "")) return;
    syncing.current = true;
    apiRef.current.setValue(nextValue);
    queueMicrotask(() => {
      syncing.current = false;
    });
  }, [value]);

  return api;
}
