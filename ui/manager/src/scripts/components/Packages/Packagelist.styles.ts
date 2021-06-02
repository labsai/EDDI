import { CSSProperties } from 'react';
import { LIGHT_GREY_COLOR } from '../../../styles/DefaultStylingProperties';

const styles: { [key: string]: IExtendedCSSProperties } = {
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  packageList: {
    paddingBottom: '250px',
  },
  pkg: {
    borderBottom: `2px solid ${LIGHT_GREY_COLOR}`,
    paddingBottom: '5px',
  },
};
export default styles;
