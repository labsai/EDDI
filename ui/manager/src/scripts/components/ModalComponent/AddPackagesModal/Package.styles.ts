import { CSSProperties } from 'react';
import {
  DARK_BLUE_COLOR,
  GREEN_COLOR,
  GREY_COLOR,
  LARGE_FONT,
  LIGHT_GREY_COLOR,
  LIGHT_GREY_COLOR2,
  MEDIUM_FONT,
  MEDIUM_FONT2,
  SMALL_FONT2,
} from '../../../../styles/DefaultStylingProperties';

const styles: { [key: string]: IExtendedCSSProperties } = {
  packageName: {
    color: GREY_COLOR,
    fontSize: LARGE_FONT,
    marginLeft: '18px',
    marginRight: '10px',
    marginTop: '6px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
  },
  centerFlex: {
    flex: 1,
  },
  modifiedDate: {
    color: GREY_COLOR,
    fontSize: SMALL_FONT2,
    marginTop: '2px',
  },
  content: {
    marginTop: '30px',
    marginLeft: '50px',
  },
  topContent: {
    display: 'flex',
    height: '36px',
  },
  button: {
    height: '36px',
    width: '36px',
    minHeight: '36px',
    minWidth: '36px',
    backgroundColor: LIGHT_GREY_COLOR,
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    fontSize: MEDIUM_FONT2,
    textAlign: 'center',
    cursor: 'pointer',
  },
  buttonChecked: {
    backgroundColor: GREEN_COLOR,
  },
  bottomContent: {
    marginTop: '5px',
    marginLeft: '54px',
    marginRight: '50px',
  },
  text: {
    maxWidth: '600px',
  },
  truncate: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  descriptionContainer: {
    display: 'flex',
    color: GREY_COLOR,
    fontSize: '13px',
    maxWidth: '600px',
  },
  descriptionButton: {
    fontSize: SMALL_FONT2,
    color: DARK_BLUE_COLOR,
    whiteSpace: 'nowrap',
  },
  versionSelect: {
    position: 'relative',
    marginRight: '10px',
  },
  versionName: {
    backgroundColor: LIGHT_GREY_COLOR2,
    borderRadius: '100px',
    color: DARK_BLUE_COLOR,
    fontSize: MEDIUM_FONT,
    height: '20px',
    marginTop: '6px',
    padding: '2px 10px',
    textAlign: 'center',
    width: '40px',
  },
};
export default styles;
