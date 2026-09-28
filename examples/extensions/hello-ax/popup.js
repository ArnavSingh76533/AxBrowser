const result = document.querySelector('#result');
chrome.runtime.sendMessage({type: 'hello'}).then(value => result.textContent = value.greeting).catch(error => result.textContent = error.message);
document.querySelector('#inspect').onclick = async () => {
  try { const tabs = await chrome.tabs.query({active: true, currentWindow: true}); result.textContent = tabs[0]?.url || 'No public tab'; }
  catch (error) { result.textContent = error.message; }
};
document.querySelector('#options').onclick = () => chrome.runtime.openOptionsPage();
