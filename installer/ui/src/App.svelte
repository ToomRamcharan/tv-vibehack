<script>
  import { onMount } from 'svelte';
  import { fly } from 'svelte/transition';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { w } from './lib/wizard.svelte.js';
  import { t } from './lib/i18n.js';
  import Icon from './lib/Icon.svelte';
  import Welcome from './screens/Welcome.svelte';
  import Prepare from './screens/Prepare.svelte';
  import Connect from './screens/Connect.svelte';
  import Working from './screens/Working.svelte';
  import Done from './screens/Done.svelte';

  const SCREENS = { welcome: Welcome, prepare: Prepare, connect: Connect, working: Working, done: Done };
  const PROGRESS = { prepare: 1, connect: 2, working: 3, done: 4 };

  const Screen = $derived(SCREENS[w.screen]);
  const step = $derived(PROGRESS[w.screen] ?? 0);
  const rail = $derived([
    t('rail_prepare'),
    t('rail_connect'),
    t(w.mode === 'install' ? 'rail_install' : 'rail_restore'),
    t('rail_done'),
  ]);
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  let closeDialog;

  onMount(() => {
    const win = getCurrentWindow();
    // An in-page dialog, not window.confirm: macOS webviews silently return false for confirm().
    const unlisten = win.onCloseRequested((event) => {
      if (!w.flowRunning) return;
      event.preventDefault();
      closeDialog.showModal();
    });
    return () => unlisten.then((fn) => fn());
  });

  const closeAnyway = () => getCurrentWindow().destroy();
</script>

<div class="flex h-full bg-base-200 text-base-content">
  <aside class="hidden w-56 shrink-0 flex-col justify-between px-6 py-8 sm:flex">
    <div>
      <div class="flex items-center gap-2.5">
        <span class="grid size-9 place-items-center rounded-xl bg-primary text-primary-content shadow-sm">
          <Icon name="tv" class="size-5" />
        </span>
        <div class="leading-tight">
          <p class="text-sm font-semibold tracking-tight">NLRC Setup</p>
          <p class="text-xs opacity-60">{t('brand_sub')}</p>
        </div>
      </div>

      <ul class="steps steps-vertical mt-10 text-sm">
        {#each rail as label, i (i)}
          <li class="step" class:step-primary={step > i} class:font-medium={step === i + 1}>
            <span class:opacity-60={step < i + 1}>{label}</span>
          </li>
        {/each}
      </ul>
    </div>
    <p class="text-xs leading-relaxed opacity-50">{t('rail_safe')}</p>
  </aside>

  <main class="flex min-w-0 flex-1 p-3 sm:py-4 sm:pr-4 sm:pl-0">
    <div class="flex flex-1 overflow-y-auto rounded-box border border-base-300 bg-base-100 shadow-sm">
      <div class="m-auto w-full max-w-xl px-8 py-10 sm:px-12">
        {#if step}
          <p class="mb-2 text-xs font-medium tracking-widest text-primary uppercase sm:hidden">{t('step_of', { n: step })}</p>
        {/if}
        {#key w.visit}
          <div in:fly={{ y: 10, duration: reduceMotion ? 0 : 220 }}>
            <Screen />
          </div>
        {/key}
      </div>
    </div>
  </main>
</div>

<dialog bind:this={closeDialog} class="modal">
  <div class="modal-box">
    <h3 class="flex items-center gap-2 text-lg font-semibold">
      <Icon name="alert" class="size-5 text-warning" /> {t('close_title')}
    </h3>
    <p class="py-4 opacity-80">{t('close_prompt')}</p>
    <div class="modal-action">
      <form method="dialog"><button class="btn btn-ghost">{t('keep_going')}</button></form>
      <button class="btn btn-error" onclick={closeAnyway}>{t('close_anyway')}</button>
    </div>
  </div>
</dialog>
