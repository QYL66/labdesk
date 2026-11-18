/** Execute the real page effects and store with a controlled router/hook scheduler. */
import assert from "node:assert/strict";
import { build } from "esbuild";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = resolve(root, ".output/agent-navigation");
mkdirSync(output, { recursive: true });
let store;
let unsubscribe;
let dispose = () => {};
try {
  await build({
    stdin: {
      contents:
        'export { AgentChatPage } from "./src/pages/AgentChatPage"; export { useAgentChatStore } from "./src/stores/agentChatStore"; export { api } from "./src/services/api";',
      resolveDir: root,
      loader: "ts"
    },
    outfile: resolve(output, "page.mjs"),
    bundle: true,
    platform: "node",
    format: "esm",
    packages: "external",
    tsconfig: resolve(root, "tsconfig.app.json"),
    define: { "import.meta.env": "{}" },
    plugins: [
      {
        name: "page-environment",
        setup(builder) {
          builder.onResolve(
            {
              filter:
                /^(react|react-router-dom|@\/components\/agent\/.*|@\/stores\/agentChatStore)$/
            },
            (args) => {
              if (!args.importer.endsWith("AgentChatPage.tsx")) return;
              return { path: args.path, namespace: "page-environment" };
            }
          );
          builder.onLoad({ filter: /.*/, namespace: "page-environment" }, ({ path }) => {
            let contents;
            if (path === "react") {
              contents = ["useState", "useMemo", "useRef", "useEffect"]
                .map(
                  (name) =>
                    `export const ${name} = (...args) => globalThis.__agentNavigation.${name}(...args);`
                )
                .join("\n");
            } else if (path === "react-router-dom") {
              contents =
                "export const useParams = () => ({sessionId: globalThis.__agentNavigation.sessionId}); export const useNavigate = () => globalThis.__agentNavigation.navigate;";
            } else if (path.startsWith("@/components/")) {
              contents = `export const ${path.split("/").at(-1)} = () => null;`;
            } else {
              contents =
                'import { useAgentChatStore as store } from "./src/stores/agentChatStore"; export const useAgentChatStore = Object.assign(() => store.getState(), store);';
            }
            return { contents, resolveDir: root, loader: "ts" };
          });
        }
      }
    ]
  });
  const module = await import(pathToFileURL(resolve(output, "page.mjs")));
  store = module.useAgentChatStore;
  const reads = [];
  let finishSlowHistory;
  module.api.defaults.adapter = async (config) => {
    let data;
    if (config.url.endsWith("/conversations")) {
      data = ["old", "other", "slow"].map((conversationId) => ({
        conversationId,
        title: conversationId
      }));
    } else {
      const id = config.url.match(/\/conversations\/([^/]+)\/messages$/)?.[1];
      assert.ok(id, `Unexpected request: ${config.url}`);
      reads.push(id);
      if (id === "slow")
        await new Promise((resolve) => {
          finishSlowHistory = resolve;
        });
      data = [{ id: `${id}-answer`, role: "assistant", content: `${id} history` }];
    }
    return { data: { code: "0", data }, status: 200, statusText: "OK", headers: {}, config };
  };

  // Store updates may render before React Router commits navigation. Keep them separate.
  const slots = [];
  let cursor = 0;
  let dirty = true;
  let effects = [];
  let navigations = [];
  const changed = (before, after) =>
    !before ||
    before.length !== after.length ||
    after.some((value, i) => !Object.is(value, before[i]));
  const harness = {
    sessionId: "old",
    navigate(path) {
      navigations.push(path);
    },
    useState(initial) {
      const index = cursor++;
      slots[index] ??= { value: initial };
      return [
        slots[index].value,
        (value) => {
          if (!Object.is(value, slots[index].value)) {
            slots[index].value = value;
            dirty = true;
          }
        }
      ];
    },
    useMemo(factory, deps) {
      const index = cursor++;
      if (changed(slots[index]?.deps, deps)) slots[index] = { deps, value: factory() };
      return slots[index].value;
    },
    useRef(initial) {
      const index = cursor++;
      slots[index] ??= { value: { current: initial } };
      return slots[index].value;
    },
    useEffect(callback, deps) {
      const index = cursor++;
      if (changed(slots[index]?.deps, deps)) {
        const previous = slots[index];
        slots[index] = { deps, cleanup: previous?.cleanup };
        effects.push(() => {
          previous?.cleanup?.();
          slots[index].cleanup = callback();
        });
      }
    }
  };
  globalThis.__agentNavigation = harness;
  unsubscribe = store.subscribe(() => {
    dirty = true;
  });
  dispose = () => slots.forEach((slot) => slot.cleanup?.());
  const settle = async () => {
    for (let attempts = 0; attempts < 20; attempts++) {
      if (dirty) {
        dirty = false;
        cursor = 0;
        effects = [];
        module.AgentChatPage();
        effects.forEach((effect) => effect());
      }
      await new Promise((resolve) => setTimeout(resolve, 0));
      if (!dirty) return;
    }
    throw new Error("Page did not settle");
  };
  const route = async (id) => {
    harness.sessionId = id;
    dirty = true;
    await settle();
  };
  const blank = () => {
    assert.equal(store.getState().currentSessionId, null);
    assert.deepEqual(store.getState().messages, []);
  };

  await settle();
  assert.equal(store.getState().messages[0].content, "old history");
  const beforeNew = reads.length;
  store.getState().startNewChat();
  await settle(); // The old URL is still visible here: the regression's critical ordering.
  blank();
  assert.equal(reads.length, beforeNew, "New chat must not reload the previous URL's history");
  await route(undefined);
  blank();
  assert.deepEqual(navigations, [], "New chat must not redirect back to the old session");
  console.log("ok New chat stays blank before and after the router commits");

  store.setState({ sessions: [...store.getState().sessions] });
  await settle();
  blank();
  await route("other");
  assert.equal(store.getState().messages[0].content, "other history");
  await route(undefined); // Browser Back to /chat must also produce a fresh draft.
  blank();
  assert.deepEqual(navigations, []);
  console.log("ok History switching, list refresh and Back to /chat preserve the selected view");

  // This is the store state produced by the first SSE meta frame of a new draft.
  store.setState({
    currentSessionId: "created",
    isCreatingNew: false,
    sessions: [...store.getState().sessions, { id: "created", title: "New question" }],
    messages: [{ id: "question", role: "user", content: "New question" }],
    isStreaming: true
  });
  await settle();
  assert.deepEqual(navigations, ["/chat/created"]);
  await route("created");
  assert.equal(store.getState().messages[0].content, "New question");
  assert.equal(
    reads.includes("created"),
    false,
    "Meta navigation must not overwrite a live answer with history"
  );
  navigations = [];
  console.log("ok First-message meta updates the URL without replacing the live conversation");

  await route("slow");
  assert.equal(typeof finishSlowHistory, "function");
  store.getState().startNewChat();
  await settle();
  await route(undefined);
  finishSlowHistory();
  await settle();
  blank();
  assert.deepEqual(navigations, []);
  console.log("ok A late history response cannot replace the new draft");

  await route("missing");
  blank();
  assert.deepEqual(navigations, ["/chat"]);
  await route(undefined);
  blank();
  console.log("ok Missing or deleted session URLs return to a blank draft");
  console.log("5/5 passed");
} finally {
  dispose();
  unsubscribe?.();
  store?.getState().reset();
  delete globalThis.__agentNavigation;
  rmSync(output, { recursive: true, force: true });
}
