<script>
  import { onMount } from 'svelte';
  import { w, go, exclusive, text, scanTvs, connectTv, runFlow } from '../lib/wizard.svelte.js';
  import Icon from '../lib/Icon.svelte';
  import { t, rich, fromBackend, androidName } from '../lib/i18n.js';

  let scanning = $state(true);
  let ips = $state([]);
  let ip = $state('');
  let connecting = $state(false);
  let error = $state('');
  let tv = $state(null);
  let unsupported = $state('');

  onMount(() => {
    exclusive(async () => {
      try {
        ips = await scanTvs();
      } catch {
        // The scan is only a shortcut; typing an IP still works.
      }
      scanning = false;
    });
  });

  function connect(addr) {
    return exclusive(async () => {
      error = '';
      tv = null;
      unsupported = '';
      connecting = true;
      try {
        const r = await connectTv(addr);
        tv = r;
        // Restore runs on any TV; only Install is refused on unsupported ones.
        if (r.support?.Unsupported !== undefined && w.mode === 'install') unsupported = r.support.Unsupported;
      } catch (e) {
        error = text(e);
      } finally {
        connecting = false;
      }
    });
  }

  function submit(e) {
    e.preventDefault();
    connect(ip);
  }
</script>

<h1 class="text-2xl font-semibold tracking-tight">{t('connect_title')}</h1>

{#if tv}
  <div class="mt-8 flex items-center gap-4 rounded-box border border-base-300 bg-base-200/60 p-5">
    <span class="grid size-12 shrink-0 place-items-center rounded-xl bg-secondary text-secondary-content">
      <Icon name="tv" class="size-6" />
    </span>
    <div class="min-w-0 flex-1">
      <p class="truncate font-semibold">{tv.model}</p>
      <p class="text-sm opacity-70">{androidName(tv.android)}</p>
    </div>
    {#if !unsupported}<span class="badge badge-soft badge-success">{t('connected')}</span>{/if}
  </div>

  {#if unsupported}
    <div role="alert" class="alert alert-soft alert-warning mt-4">
      <Icon name="alert" class="size-5" />
      <span>{fromBackend(unsupported)}</span>
    </div>
    <div class="mt-10 flex justify-end">
      <button class="btn btn-primary" onclick={() => go('welcome')}>{t('back_to_start')}</button>
    </div>
  {:else}
    <div class="mt-10 flex justify-between">
      <button class="btn btn-ghost" onclick={() => go('prepare')}><Icon name="back" class="size-4" /> {t('back')}</button>
      <button class="btn btn-primary" disabled={w.busy} onclick={() => runFlow(false)}>
        {t('continue')} <Icon name="next" class="size-4" />
      </button>
    </div>
  {/if}
{:else}
  <section class="mt-8">
    <h2 class="text-sm font-medium opacity-70">{t('found_title')}</h2>
    {#if scanning}
      <div class="mt-3 flex items-center gap-3 rounded-field border border-dashed border-base-300 p-4 text-sm opacity-70">
        <span class="loading loading-dots loading-sm"></span> {t('scanning')}
      </div>
    {:else if ips.length}
      <div class="mt-3 grid gap-2">
        {#each ips as addr (addr)}
          <button
            class="btn h-auto justify-start gap-3 border-base-300 bg-base-100 py-3 font-normal hover:border-primary/40"
            disabled={w.busy}
            onclick={() => connect(addr)}
          >
            <Icon name="tv" class="size-5 text-primary" />
            <span class="font-mono">{addr}</span>
            <Icon name="next" class="ml-auto size-4 opacity-50" />
          </button>
        {/each}
      </div>
    {:else}
      <p class="mt-3 text-sm opacity-70">{t('none_found')}</p>
    {/if}
  </section>

  <div class="divider my-6 text-xs opacity-60">{t('or_type')}</div>

  <form class="join w-full" onsubmit={submit}>
    <label class="input join-item w-full">
      <Icon name="wifi" class="size-4 opacity-50" />
      <input
        bind:value={ip}
        type="text"
        inputmode="decimal"
        placeholder="192.168.1.50"
        autocomplete="off"
        spellcheck="false"
        aria-label={t('ip_label')}
        class="font-mono"
        disabled={w.busy}
      />
    </label>
    <button class="btn btn-primary join-item" type="submit" disabled={w.busy}>{t('connect_btn')}</button>
  </form>
  <p class="mt-2 text-xs opacity-60">{t('ip_hint')}</p>

  {#if connecting}
    <div role="status" class="alert alert-soft alert-info mt-6">
      <span class="loading loading-spinner loading-sm"></span>
      <span>
        {@html rich(t('connecting'))}
        <span class="block text-xs opacity-70">{t('connecting_wait')}</span>
      </span>
    </div>
  {/if}
  {#if error}
    <div role="alert" class="alert alert-soft alert-error mt-6">
      <Icon name="alert" class="size-5" />
      <span>{fromBackend(error)}</span>
    </div>
  {/if}

  <div class="mt-10">
    <button class="btn btn-ghost" disabled={w.busy} onclick={() => go('prepare')}>
      <Icon name="back" class="size-4" /> {t('back')}
    </button>
  </div>
{/if}
