const getIdsFromPath = () => {
  const isPackagePage = location.pathname.includes('packageview');
  const isBotPage = location.pathname.includes('botview');
  const urlSearchParams = new URLSearchParams(location.search);
  const botId = isBotPage
    ? location.pathname.split('/')?.[3]
    : urlSearchParams.get('botId');
  const packageId = isPackagePage
    ? location.pathname.split('/')?.[3]
    : urlSearchParams.get('packageId');

  console.log('botId: ', botId);
  console.log('packageId: ', packageId);
  return { botId, packageId };
};

export const isBotPage = () => location.pathname.includes('botview');
export const isPackagePage = () => location.pathname.includes('packageview');

export default getIdsFromPath;
