import type { Skill } from "./skills-store";
import { deleteSkill, getSkill, listSkills, saveSkill } from "./skills-store";

export type SkillToolArgs = {
  action: "get" | "list" | "create" | "rewrite" | "update" | "delete";
  name?: string;
  url?: string;
  includeLibraryCode?: boolean;
  data?: Partial<Skill>;
  updates?: Record<string, { old_string: string; new_string: string }>;
};

function stripLibrary(skill: Skill): Skill {
  return { ...skill, library: "" };
}

function replaceAll(value: string, from: string, to: string): string {
  if (!from) return value;
  return value.split(from).join(to);
}

function applyUpdates(
  skill: Skill,
  updates: Record<string, { old_string: string; new_string: string }>,
) {
  const next = { ...skill };
  for (const [field, patch] of Object.entries(updates)) {
    if (!patch) continue;
    const key = field as keyof Skill;
    const current = typeof next[key] === "string" ? (next[key] as string) : null;
    if (current == null) continue;
    next[key] = replaceAll(current, patch.old_string, patch.new_string) as never;
  }
  return next;
}

export async function executeSkillTool(
  args: SkillToolArgs,
  resolveUrl?: () => Promise<string | null>,
): Promise<{ text: string; details?: unknown }> {
  const action = args.action;
  if (!action) throw new Error("Missing skill action");

  if (action === "list") {
    const url = args.url ?? (await resolveUrl?.()) ?? undefined;
    const skills = await listSkills(url);
    const items = skills.map((skill) => stripLibrary(skill));
    const text =
      items.length === 0
        ? "No skills found."
        : items.map((skill) => `- ${skill.name}: ${skill.shortDescription}`).join("\n");
    return { text, details: { skills: items } };
  }

  if (action === "get") {
    if (!args.name) throw new Error("Missing skill name");
    const skill = await getSkill(args.name);
    if (!skill) throw new Error(`Skill not found: ${args.name}`);
    const payload = args.includeLibraryCode ? skill : stripLibrary(skill);
    const text = `${payload.name}\n${payload.shortDescription}`;
    return { text, details: payload };
  }

  if (action === "delete") {
    if (!args.name) throw new Error("Missing skill name");
    const deleted = await deleteSkill(args.name);
    return { text: deleted ? `Deleted skill ${args.name}` : `Skill not found: ${args.name}` };
  }

  if (action === "create" || action === "rewrite") {
    if (!args.data) throw new Error("Missing skill data");
    if (!args.data.name) throw new Error("Missing skill name");
    if (action === "create") {
      const existing = await getSkill(args.data.name);
      if (existing) throw new Error(`Skill already exists: ${args.data.name}`);
    }
    if (action === "rewrite" && args.name) {
      const existing = await getSkill(args.name);
      if (!existing) throw new Error(`Skill not found: ${args.name}`);
    }
    const skill: Skill = {
      name: args.data.name,
      domainPatterns: args.data.domainPatterns ?? [],
      shortDescription: args.data.shortDescription ?? "",
      description: args.data.description ?? "",
      createdAt: args.data.createdAt ?? new Date().toISOString(),
      lastUpdated: args.data.lastUpdated ?? new Date().toISOString(),
      examples: args.data.examples ?? "",
      library: args.data.library ?? "",
    };
    const saved = await saveSkill(skill);
    if (action === "rewrite" && args.name && args.name !== saved.name) {
      await deleteSkill(args.name);
    }
    return { text: `Saved skill ${saved.name}`, details: stripLibrary(saved) };
  }

  if (action === "update") {
    if (!args.name) throw new Error("Missing skill name");
    if (!args.updates) throw new Error("Missing updates");
    const skill = await getSkill(args.name);
    if (!skill) throw new Error(`Skill not found: ${args.name}`);
    const next = applyUpdates(skill, args.updates);
    const saved = await saveSkill(next);
    return { text: `Updated skill ${saved.name}`, details: stripLibrary(saved) };
  }

  throw new Error(`Unknown skill action: ${action}`);
}
