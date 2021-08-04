import { Dispatch } from 'redux';
import {
  hideLoader,
  showLoader,
} from '../../../../scripts/actions/SystemActions';
import { axiosExportBot, IBot } from '../AxiosFunctions';

const exportBot = (bot: IBot, dispatch: Dispatch) => {
  dispatch(showLoader());
  axiosExportBot(bot.id, bot.version).then((link) => {
    dispatch(hideLoader());
    if (link) {
      window?.open(link, '_blank');
    }
  });
};

export default exportBot;
