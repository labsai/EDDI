import { CSSProperties } from 'react';
import {
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LARGE_FONT3,
  SMALL_FONT,
} from '../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  topHeader: {
    display: 'flex',
  },
  title: {
    color: DARK_BLUE_COLOR,
    fontSize: LARGE_FONT3,
  },
  lastModified: {
    color: GREY_COLOR,
    fontSize: SMALL_FONT,
    marginLeft: 'auto',
    marginRight: '0',
    marginTop: '14px',
  },
};
export default styles;
