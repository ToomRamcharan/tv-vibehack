<script>
  import { w, go } from '../lib/wizard.svelte.js';
  import Icon from '../lib/Icon.svelte';
  import { t } from '../lib/i18n.js';

  const CHOICES = [
    { mode: 'install', icon: 'download', title: t('install_title'), text: t('install_text') },
    { mode: 'restore', icon: 'restore', title: t('restore_title'), text: t('restore_text') },
  ];

  function choose(mode) {
    w.mode = mode;
    go('prepare');
  }
</script>

<div class="text-center">
  <span class="mx-auto grid size-16 place-items-center rounded-2xl bg-secondary text-secondary-content">
    <Icon name="tv" class="size-8" />
  </span>
  <h1 class="mt-6 text-3xl font-semibold tracking-tight">{t('welcome_title')}</h1>
  <p class="mt-2 opacity-70">{t('welcome_lead')}</p>
</div>

<div class="mt-10 grid gap-3">
  {#each CHOICES as c, i (c.mode)}
    <button
      class="group flex items-center gap-4 rounded-box border p-5 text-left transition-colors duration-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:opacity-50
        {i === 0
        ? 'border-primary bg-primary text-primary-content hover:brightness-110'
        : 'border-base-300 bg-base-100 hover:border-primary/50 hover:bg-base-200'}"
      disabled={w.busy}
      onclick={() => choose(c.mode)}
    >
      <span
        class="grid size-11 shrink-0 place-items-center rounded-xl {i === 0
          ? 'bg-primary-content/15'
          : 'bg-secondary text-secondary-content'}"
      >
        <Icon name={c.icon} class="size-5" />
      </span>
      <span class="flex-1">
        <span class="block font-semibold">{c.title}</span>
        <span class="block text-sm opacity-75">{c.text}</span>
      </span>
      <Icon name="next" class="size-5 opacity-60 transition-opacity duration-100 group-hover:opacity-100" />
    </button>
  {/each}
</div>
