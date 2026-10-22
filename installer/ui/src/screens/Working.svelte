<script>
  import { w, work, go, runFlow, REPLACE_PROMPT, STEPS } from '../lib/wizard.svelte.js';
  import Icon from '../lib/Icon.svelte';
  import { t, fromBackend } from '../lib/i18n.js';
</script>

<h1 class="text-2xl font-semibold tracking-tight">{t(work.title)}</h1>

<ul class="mt-8 space-y-1">
  {#each STEPS[w.mode] as id (id)}
    {@const row = work.rows[id]}
    <li
      class="flex gap-3 rounded-field px-3 py-2.5 transition-colors"
      class:bg-base-200={row?.state === 'running'}
    >
      <span class="mt-0.5 grid size-6 shrink-0 place-items-center">
        {#if row?.state === 'running'}
          <span class="loading loading-spinner loading-sm text-primary"></span>
        {:else if row?.state === 'done'}
          <span class="grid size-6 place-items-center rounded-full bg-success text-success-content">
            <Icon name="check" class="size-3.5" />
          </span>
        {:else if row?.state === 'failed'}
          <span class="grid size-6 place-items-center rounded-full bg-error text-error-content">
            <Icon name="x" class="size-3.5" />
          </span>
        {:else}
          <span class="size-2 rounded-full bg-base-300"></span>
        {/if}
      </span>
      <span class="min-w-0">
        <span class:opacity-50={row?.state === 'pending'} class:font-medium={row?.state === 'running'}>{t(`step_${id}`)}</span>
        {#if row?.message}<span class="mt-0.5 block text-sm text-error">{fromBackend(row.message)}</span>{/if}
      </span>
    </li>
  {/each}
</ul>

{#if work.replace}
  <div class="mt-6 rounded-box border border-warning/40 bg-warning/10 p-5">
    <p class="flex gap-3">
      <Icon name="alert" class="mt-0.5 size-5 shrink-0 text-warning" />
      <span>{fromBackend(REPLACE_PROMPT)}</span>
    </p>
    <div class="mt-4 flex justify-end gap-2">
      <button class="btn btn-ghost" disabled={w.busy} onclick={() => go('welcome')}>{t('no')}</button>
      <button class="btn btn-primary" disabled={w.busy} onclick={() => runFlow(true)}>{t('yes')}</button>
    </div>
  </div>
{/if}

{#if work.error}
  <div role="alert" class="alert alert-soft alert-error mt-6">
    <Icon name="alert" class="size-5" />
    <span>{fromBackend(work.error)}</span>
  </div>
{/if}

{#if work.failed}
  <div class="mt-10 flex justify-between">
    <button class="btn btn-ghost" disabled={w.busy} onclick={() => go('connect')}>
      <Icon name="back" class="size-4" /> {t('back')}
    </button>
    {#if work.reconnect}
      <button class="btn btn-primary" disabled={w.busy} onclick={() => go('connect')}>
        <Icon name="wifi" class="size-4" /> {t('reconnect')}
      </button>
    {:else}
      <button class="btn btn-primary" disabled={w.busy} onclick={() => runFlow(false)}>
        <Icon name="retry" class="size-4" /> {t('try_again')}
      </button>
    {/if}
  </div>
{/if}
