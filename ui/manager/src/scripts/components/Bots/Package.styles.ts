import { CSSProperties } from 'react';
import {
  BLUE_COLOR2,
  MEDIUM_FONT,
  RED_COLOR,
} from '../../../styles/DefaultStylingProperties';

const styles: { [key: string]: IExtendedCSSProperties } = {
  botPackageName: {
    color: BLUE_COLOR2,
    display: 'inline-block',
    fontFamily: 'Roboto',
    fontSize: MEDIUM_FONT,
    lineHeight: '19px',
    overflow: 'hidden',
    textAlign: 'left',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    width: '100%',
  },
  botPackageLastModifiedOn: {
    color: 'black',
    float: 'left',
    fontFamily: 'Roboto',
    fontSize: '11px',
    whiteSpace: 'nowrap',
  },
  botPackageButton: {
    backgroundColor: 'white',
    border: 'none',
    color: BLUE_COLOR2,
    display: 'inline-block',
    minHeight: '50px',
    width: '150px',
    cursor: 'pointer',
  },
  updatePackages: {
    marginTop: '10px',
    width: '100px',
  },
  hasNewVersion: {
    color: RED_COLOR,
  },
};
export default styles;
