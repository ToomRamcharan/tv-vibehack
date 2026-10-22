<script>
  import { go } from '../lib/wizard.svelte.js';
  import Icon from '../lib/Icon.svelte';
  import { t, rich } from '../lib/i18n.js';
</script>

<h1 class="text-2xl font-semibold tracking-tight">{t('prepare_title')}</h1>
<p class="mt-1 opacity-70">{t('prepare_lead')}</p>

<ol class="mt-8 space-y-5">
  {#each ['prepare_1', 'prepare_2', 'prepare_3'] as key, i (key)}
    <li class="flex gap-4">
      <span class="badge badge-primary size-7 shrink-0 rounded-full p-0 font-semibold">{i + 1}</span>
      <p>{@html rich(t(key))}</p>
    </li>
  {/each}
</ol>

<div role="note" class="alert alert-soft alert-info mt-8 text-sm">
  <Icon name="info" class="size-5" />
  <span>{t('xiaomi_note')}</span>
</div>

<details class="collapse-arrow collapse mt-3 rounded-field border border-base-300">
  <summary class="collapse-title text-sm font-medium">{t('warned_summary')}</summary>
  <div class="collapse-content space-y-2 text-sm opacity-80">
    <p>{@html rich(t('warned_windows'))}</p>
    <p>{@html rich(t('warned_mac'))}</p>
  </div>
</details>

<div class="mt-10 flex justify-between">
  <button class="btn btn-ghost" onclick={() => go('welcome')}><Icon name="back" class="size-4" /> {t('back')}</button>
  <button class="btn btn-primary" onclick={() => go('connect')}>
    {t('ready')} <Icon name="next" class="size-4" />
  </button>
</div>
