import * as moment from 'moment';

export const getDate = (date: number): string => {
  return moment(date).format('DD.MM.YYYY');
};
