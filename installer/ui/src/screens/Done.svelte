<script>
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { w, exclusive } from '../lib/wizard.svelte.js';
  import Icon from '../lib/Icon.svelte';
  import { t, rich } from '../lib/i18n.js';

  const close = () => exclusive(() => getCurrentWindow().close());
</script>

<div class="text-center">
  <span class="mx-auto grid size-16 place-items-center rounded-full bg-success/15 text-success">
    <Icon name="success" class="size-9" />
  </span>
  {#if w.mode === 'install'}
    <h1 class="mt-6 text-3xl font-semibold tracking-tight">{t('done_install_title')}</h1>
    <p class="mt-2 opacity-70">{@html rich(t('done_install_text'))}</p>
  {:else}
    <h1 class="mt-6 text-3xl font-semibold tracking-tight">{t('done_restore_title')}</h1>
    <p class="mt-2 opacity-70">{t('done_restore_text')}</p>
  {/if}
  <button class="btn btn-primary mt-10 px-10" disabled={w.busy} onclick={close}>{t('close')}</button>
</div>
