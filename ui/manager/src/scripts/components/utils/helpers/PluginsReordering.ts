import { IPluginExtensions } from '../AxiosFunctions';

// reordering the result
const reorder = (
  list: IPluginExtensions[],
  startIndex: number,
  endIndex: number,
) => {
  const result = Array.from(list);
  const [removed] = result.splice(startIndex, 1);
  result.splice(endIndex, 0, removed);

  return result;
};

export default reorder;
