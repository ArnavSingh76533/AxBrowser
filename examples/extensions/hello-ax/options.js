const input = document.querySelector('#greeting');
chrome.storage.local.get({greeting: 'Hello from AxBrowser'}).then(value => input.value = value.greeting);
document.querySelector('#save').onclick = async () => {
  await chrome.storage.local.set({greeting: input.value});
  document.querySelector('#result').textContent = 'Saved. Reload example.com to see your greeting.';
};
